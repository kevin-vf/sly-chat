package io.slychat.messenger.core.persistence.sqlite

import io.slychat.messenger.core.crypto.generateUploadId
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.core.randomName
import io.slychat.messenger.core.randomRemoteFile
import io.slychat.messenger.core.randomUserMetadata
import io.slychat.messenger.testutils.desc
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.*

class SQLiteUploadPersistenceManagerTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadLibrary() {
            SQLitePreKeyPersistenceManager::class.java.loadSQLiteLibraryFromResources()
        }
    }

    private lateinit var persistenceManager: SQLitePersistenceManager
    private lateinit var uploadPersistenceManager: SQLiteUploadPersistenceManager
    private lateinit var fileListPersistenceManager: SQLiteFileListPersistenceManager

    @Before
    fun before() {
        persistenceManager = SQLitePersistenceManager(null, null)
        persistenceManager.init()

        uploadPersistenceManager = SQLiteUploadPersistenceManager(persistenceManager)
        fileListPersistenceManager = SQLiteFileListPersistenceManager(persistenceManager)
    }

    @After
    fun after() {
        persistenceManager.shutdown()
    }

    private fun randomFilePath(): String {
        return "/path/file.ext"
    }

    private fun insertUploadFull(error: UploadError? = null, directory: String? = null): UploadInfo {
        val userMetadata = randomUserMetadata(directory)
        val file = randomRemoteFile(userMetadata = userMetadata)
        val upload = randomUpload(file.id, error)
        val info = UploadInfo(upload, file)
        uploadPersistenceManager.add(info).get()
        return info
    }

    private fun insertUpload(error: UploadError? = null): Upload {
        return insertUploadFull(error).upload
    }

    private fun randomUpload(fileId: String, error: UploadError? = null): Upload {
        val parts = listOf(
            UploadPart(1, 0, 10, 11, false),
            UploadPart(2, 10, 5, 6, false)
        )

        return Upload(
            generateUploadId(),
            fileId,
            UploadState.PENDING,
            randomName(),
            randomFilePath(),
            null,
            false,
            error, parts
        )
    }

    private fun getUpload(uploadId: String, message: String = "Upload not in db"): Upload {
        return assertNotNull(uploadPersistenceManager.get(uploadId).get(), message)
    }

    @Test
    fun `add should insert a new upload entry`() {
        val file = randomRemoteFile()

        val upload = randomUpload(file.id).copy(
            isEncrypted = true,
            cachePath = "/cache/path"
        )

        uploadPersistenceManager.add(UploadInfo(upload, file)).get()

        val actual = getUpload(upload.id, "Upload not inserted in db")

        assertThat(actual).apply {
            describedAs("Should match the inserted upload")
            isEqualToComparingFieldByField(upload)
        }
    }

    @Test
    fun `add should update directory index`() {
        insertUploadFull(null, directory = "/a")

        assertThat(fileListPersistenceManager.getDirectoriesAt("/").get()).desc("Should contain subdirs") {
            containsOnly("a")
        }
    }

    @Test
    fun `remove should remove an existing upload`() {
        val upload = insertUpload()
        val upload2 = insertUpload()

        val uploadIds = listOf(upload.id, upload2.id)

        uploadPersistenceManager.remove(uploadIds).get()

        uploadIds.forEach {
            assertNull(uploadPersistenceManager.get(it).get(), "Upload not removed")
        }
    }

    @Test
    fun `remove should throw if upload doesn't exist`() {
        assertFailsWith(InvalidUploadException::class) {
            uploadPersistenceManager.remove(listOf(generateUploadId())).get()
        }
    }

    @Test
    fun `setState should update the upload's state`() {
        val upload = insertUpload()

        val newState = UploadState.COMPLETE

        uploadPersistenceManager.setState(upload.id, newState).get()

        val actual = getUpload(upload.id)

        assertEquals(newState, actual.state, "State not updated")
    }

    @Test
    fun `setState should clear any pending error`() {
        val upload = insertUpload(UploadError.NETWORK_ISSUE)

        uploadPersistenceManager.setState(upload.id, UploadState.PENDING).get()

        assertNull(getUpload(upload.id).error, "Error not cleared")
    }

    @Test
    fun `setState should throw InvalidUploadException if upload doesn't exist`() {
        assertFailsWith(InvalidUploadException::class) {
            uploadPersistenceManager.setState(generateUploadId(), UploadState.COMPLETE).get()
        }
    }

    @Test
    fun `setState should remove the associated file when state is CANCELLED`() {
        val info = insertUploadFull()

        uploadPersistenceManager.setState(info.upload.id, UploadState.CANCELLED).get()

        assertNull(fileListPersistenceManager.getFile(info.file!!.id).get(), "File not removed")
    }

    @Test
    fun `setState should set the fileId to null when state is CANCELLED`() {
        val info = insertUploadFull()

        uploadPersistenceManager.setState(info.upload.id, UploadState.CANCELLED).get()

        assertNull(getUpload(info.upload.id).fileId, "fileId not set to null")
    }

    @Test
    fun `setState should update the directory index when state is CANCELLED`() {
        val info = insertUploadFull(directory = "/a")

        uploadPersistenceManager.setState(info.upload.id, UploadState.CANCELLED).get()

        assertThat(fileListPersistenceManager.getDirectoriesAt("/").get()).desc("Should remove subdir from index") {
            doesNotContain("a")
        }
    }

    @Test
    fun `setError should set a new error`() {
        val upload = insertUpload()

        val error = UploadError.INSUFFICIENT_QUOTA

        uploadPersistenceManager.setError(upload.id, error).get()

        val actual = getUpload(upload.id)

        assertEquals(error, actual.error, "Error not updated")
    }

    @Test
    fun `setError should remove an error`() {
        val upload = insertUpload(UploadError.INSUFFICIENT_QUOTA)

        uploadPersistenceManager.setError(upload.id, null).get()

        val actual = getUpload(upload.id)

        assertNull(actual.error, "Error not updated")
    }

    @Test
    fun `setError should throw InvalidUploadException if upload doesn't exist`() {
        assertFailsWith(InvalidUploadException::class) {
            uploadPersistenceManager.setError(generateUploadId(), null).get()
        }
    }

    @Test
    fun `completePart should mark part as complete`() {
        val upload = insertUpload()
        val part = upload.parts.first()

        uploadPersistenceManager.completePart(upload.id, part.n).get()

        val actual = getUpload(upload.id)
        val actualPart = actual.parts.first()

        assertTrue(actualPart.isComplete, "Part not marked as completed")
    }

    @Test
    fun `completePart should throw InvalidUploadException if upload doesn't exist`() {
        assertFailsWith(InvalidUploadException::class) {
            uploadPersistenceManager.completePart(generateUploadId(), 1).get()
        }
    }

    @Test
    fun `completePart should throw InvalidUploadPartException if part doesn't exist`() {
        val upload = insertUpload()

        assertFailsWith(InvalidUploadPartException::class) {
            uploadPersistenceManager.completePart(upload.id, upload.parts.size + 1).get()
        }
    }

    @Test
    fun `getAll should return the empty list if no uploads are present`() {
        assertThat(uploadPersistenceManager.getAll().get()).apply {
            describedAs("Should return an empty list")
            isEmpty()
        }
    }

    @Test
    fun `getAll should return all uploads`() {
        val expected = listOf(
            insertUploadFull(null),
            insertUploadFull(UploadError.INSUFFICIENT_QUOTA)
        )

        val actual = uploadPersistenceManager.getAll().get()

        assertThat(actual).apply {
            describedAs("Should return saved uploads")
            containsOnlyElementsOf(expected)
        }
    }

    @Test
    fun `getAll should handle null fileIds`() {
        val info = insertUploadFull()

        uploadPersistenceManager.setState(info.upload.id, UploadState.CANCELLED).get()

        val updated = info.upload.copy(state = UploadState.CANCELLED, fileId = null)
        val expected = UploadInfo(updated, null)

        assertThat(uploadPersistenceManager.getAll().get()).desc("Should return uploads without associated files") {
            containsOnly(expected)
        }
    }
}
