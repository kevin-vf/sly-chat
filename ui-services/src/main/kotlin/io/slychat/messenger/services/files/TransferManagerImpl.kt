package io.slychat.messenger.services.files

import io.slychat.messenger.core.persistence.DownloadInfo
import io.slychat.messenger.core.persistence.UploadInfo
import io.slychat.messenger.core.rx.plusAssign
import io.slychat.messenger.services.bindUi
import io.slychat.messenger.services.config.UserConfig
import io.slychat.messenger.services.config.UserConfigService
import nl.komponents.kovenant.Promise
import org.slf4j.LoggerFactory
import rx.Observable
import rx.Observer
import rx.Scheduler
import rx.Subscription
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import java.util.*
import java.util.concurrent.TimeUnit

class TransferManagerImpl(
    private val userConfigService: UserConfigService,
    private val uploader: Uploader,
    private val downloader: Downloader,
    private val scheduler: Scheduler,
    private val timerScheduler: Scheduler,
    networkStatus: Observable<Boolean>,
    autoRemoveCompletedTransfers: Boolean
) : TransferManager {
    private class ByType(val uploadIds: List<String>, val downloadIds: List<String>)

    private val log = LoggerFactory.getLogger(javaClass)

    private var subscriptions = CompositeSubscription()

    private val timerSubscriptions = HashMap<String, Subscription>()

    //tracks the total consecutive retry attempts per transfer
    //cleared whenever progress is received for a transfer
    //attempts begin at 1
    internal val retryAttemptCounters = HashMap<String, Int>()

    //UploadState or DownloadState
    private val previousTransferState = HashMap<String, Any>()

    private val eventsSubject = PublishSubject.create<TransferEvent>()

    override val events: Observable<TransferEvent> = Observable.merge(uploader.events, downloader.events, eventsSubject)

    override val transfers: List<TransferStatus>
        get() {
            val statuses = ArrayList<TransferStatus>()

            uploader.uploads.mapTo(statuses) { TransferStatus(Transfer.U(it.upload), it.file, it.state, UploadTransferProgress(it.progress, it.transferedBytes, it.totalBytes)) }
            downloader.downloads.mapTo(statuses) { TransferStatus(Transfer.D(it.download), it.file, it.state, it.progress) }

            return statuses
        }

    var autoRemoveCompletedTransfers: Boolean = autoRemoveCompletedTransfers
        set(value) {
            field = value
            if (value)
                removeCompleted()
        }

    init {
        subscriptions += networkStatus.subscribe {
            uploader.isNetworkAvailable = it
            downloader.isNetworkAvailable = it
        }

        downloader.simulDownloads = userConfigService.tranfersSimulDownloads

        uploader.simulUploads = userConfigService.transfersSimulUploads

        subscriptions += userConfigService.updates.subscribe { onUserConfigUpdates(it) }

        subscriptions += uploader.events.subscribe { onTransferEvent(it) }
        subscriptions += downloader.events.subscribe { onTransferEvent(it) }

        subscriptions += events
            .ofType(TransferEvent.StateChanged::class.java)
            .filter { it.state == TransferState.COMPLETE || it.state == TransferState.CANCELLED }
            .subscribe { removeTransfer(it.transfer) }

        subscriptions += events
            .ofType(TransferEvent.Added::class.java)
            .filter { it.state == TransferState.COMPLETE || it.state == TransferState.CANCELLED }
            .subscribe { removeTransfer(it.transfer) }
    }

    private fun removeTransfer(transfer: Transfer) {
        val transferIds = listOf(transfer.id)

        when (transfer) {
            is Transfer.U -> uploader.remove(transferIds)

            is Transfer.D -> downloader.remove(transferIds)
        }
    }

    private fun clearRetryData(transfer: Transfer) {
        previousTransferState.remove(transfer.id)
        retryAttemptCounters.remove(transfer.id)
    }

    private fun onTransferEvent(event: TransferEvent) {
        when (event) {
            is TransferEvent.Added -> {
                if (event.transfer.error?.isTransient ?: false)
                    startRetryTimer(event.transfer)
            }

            is TransferEvent.StateChanged -> {
                val transfer = event.transfer
                val error = transfer.error

                if (error != null) {
                    if (error.isTransient)
                        startRetryTimer(transfer)
                }
                else {
                    if (hasTransferStateChanged(transfer))
                        clearRetryData(transfer)

                    cancelTimer(transfer)
                }
            }

            is TransferEvent.Progress -> {
                //ignore progress reset events
                if (event.transfer.error == null)
                    clearRetryData(event.transfer)
            }

            is TransferEvent.Removed -> {
                event.transfers.forEach { cancelTimer(it) }
            }
        }
    }

    private fun cancelTimer(transfer: Transfer) {
        val s = timerSubscriptions[transfer.id]
        if (s != null) {
            log.info("Cancelling retry timer for {}", transfer.id)
            s.unsubscribe()
            timerSubscriptions.remove(transfer.id)
            eventsSubject.onNext(TransferEvent.UntilRetry(transfer, 0))
        }
    }

    private fun updateTransferState(transfer: Transfer) {
        previousTransferState[transfer.id] = when (transfer) {
            is Transfer.U -> transfer.upload.state
            is Transfer.D -> transfer.download.state
        }
    }

    private fun hasTransferStateChanged(transfer: Transfer): Boolean {
        val previous = previousTransferState[transfer.id] ?: return true

        val current = when (transfer) {
            is Transfer.U -> transfer.upload.state
            is Transfer.D -> transfer.download.state
        }

        return current != previous
    }

    private fun startRetryTimer(transfer: Transfer) {
        if (transfer.id in timerSubscriptions)
            return

        //cap at 2^7s
        val currentAttempt = Math.min((retryAttemptCounters[transfer.id] ?: 0) + 1, 7)
        retryAttemptCounters[transfer.id] = currentAttempt
        updateTransferState(transfer)

        log.info("Starting retry timer for transfer {} (attempt {})", transfer.id, currentAttempt)

        val timeoutSecs = nextRetryTimerValue(currentAttempt)

        timerSubscriptions[transfer.id] = Observable
            .interval(1, TimeUnit.SECONDS, timerScheduler)
            .map { it + 1 }
            .takeUntil { it == timeoutSecs }
            .observeOn(scheduler)
            .subscribe(object : Observer<Long> {
                override fun onCompleted() {
                    onRetryTimer(transfer)
                }

                override fun onNext(t: Long) {
                    eventsSubject.onNext(TransferEvent.UntilRetry(transfer, timeoutSecs - t))
                }

                override fun onError(e: Throwable) {
                    log.error("Retry interval failed: {}", e.message, e)
                }
            })

        eventsSubject.onNext(TransferEvent.UntilRetry(transfer, timeoutSecs))
    }

    private fun onRetryTimer(transfer: Transfer) {
        val id = transfer.id

        timerSubscriptions.remove(id)

        log.info("Attempting to retry transfer {}", id)

        when (transfer) {
            is Transfer.U -> uploader.clearError(id)
            is Transfer.D -> downloader.clearError(id)
        } fail {
            log.error("Failed to clear error for transfer {}: {}", id)
        }
    }

    private fun onUserConfigUpdates(keys: Collection<String>) {
        keys.forEach {
            when (it) {
                UserConfig.TRANSFERS_SIMUL_UPLOADS ->
                    uploader.simulUploads = userConfigService.transfersSimulUploads

                UserConfig.TRANSFERS_SIMUL_DOWNLOADS ->
                    downloader.simulDownloads = userConfigService.tranfersSimulDownloads
            }
        }
    }

    private fun separateByType(transferIds: List<String>): ByType {
        val uploadIds = ArrayList<String>()
        val downloadIds = ArrayList<String>()

        transferIds.forEach {
            if (uploader.contains(it))
                uploadIds.add(it)
            else if (downloader.contains(it))
                downloadIds.add(it)
            else
                throw InvalidTransferException(it)
        }

        return ByType(uploadIds, downloadIds)
    }

    override fun init() {
        uploader.init()
        downloader.init()
    }

    override fun shutdown() {
        subscriptions.clear()

        timerSubscriptions.forEach { it.value.unsubscribe() }
        timerSubscriptions.clear()

        uploader.shutdown()
        downloader.shutdown()
    }

    override fun upload(info: UploadInfo): Promise<Unit, Exception> {
        return uploader.upload(info)
    }

    override fun clearError(transferId: String): Promise<Unit, Exception> {
        return if (uploader.contains(transferId))
            uploader.clearError(transferId)
        else if (downloader.contains(transferId))
            downloader.clearError(transferId)
        else
            throw InvalidTransferException(transferId)
    }

    override fun download(downloads: List<DownloadInfo>): Promise<Unit, Exception> {
        return downloader.download(downloads)
    }

    override fun cancel(transferIds: List<String>) {
        val s = separateByType(transferIds)
        s.downloadIds.forEach {
            downloader.cancel(it)
        }

        s.uploadIds.forEach {
            uploader.cancel(it)
        }
    }

    override fun remove(transferIds: List<String>): Promise<Unit, Exception> {
        val s = separateByType(transferIds)

        return downloader.remove(s.downloadIds) bindUi {
            uploader.remove(s.uploadIds)
        }
    }

    override fun removeCompleted(): Promise<Unit, Exception> {
        val downloadsToRemove = downloader.downloads
            .filter { it.state == TransferState.COMPLETE || it.state == TransferState.CANCELLED }
            .map { it.download.id }

        val uploadsToRemove = uploader.uploads
            .filter { it.state == TransferState.COMPLETE || it.state == TransferState.CANCELLED }
            .map { it.upload.id }

        return downloader.remove(downloadsToRemove) bindUi {
            uploader.remove(uploadsToRemove)
        }
    }

    private fun nextRetryTimerValue(attemptN: Int): Long {
        val exp = Math.pow(2.0, attemptN.toDouble())
        return Random().nextInt(exp.toInt() + 1).toLong() + 1
    }
}