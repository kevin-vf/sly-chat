-- contacts.email[-UNIQUE]
ALTER TABLE contacts RENAME TO contacts_old;

CREATE TABLE IF NOT EXISTS contacts (
    id INTEGER PRIMARY KEY NOT NULL,
    email TEXT NOT NULL,
    name TEXT NOT NULL,
    allowed_message_level INTEGER NOT NULL,
    public_key TEXT NOT NULL
);

INSERT INTO
    contacts
    (id, email, name, allowed_message_level, public_key)
SELECT
    id, email, name, allowed_message_level, public_key
FROM
    contacts_old
;

DROP TABLE contacts_old;
