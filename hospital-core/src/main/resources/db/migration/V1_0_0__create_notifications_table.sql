CREATE TABLE security.notifications (
    id UUID PRIMARY KEY,
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read BOOLEAN NOT NULL,
    recipient_username VARCHAR(100) NOT NULL
);
