CREATE TABLE security.notifications (
    id UUID PRIMARY KEY,
    message VARCHAR2(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read BOOLEAN NOT NULL,
    recipient_username VARCHAR2(100) NOT NULL
);
