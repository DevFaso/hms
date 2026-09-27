package com.example.hms.repository;

import com.example.hms.model.EmailChangeSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface EmailChangeSendRepository extends JpaRepository<EmailChangeSend, UUID> {

    /** Mails sent to this address (by its hash) since the given time, by any account. */
    long countByAddressHashAndSentAtAfter(String addressHash, LocalDateTime since);

    /** Forget sends older than the counting window; nothing else reads them. */
    @Modifying
    @Query("delete from EmailChangeSend s where s.sentAt < :before")
    int deleteSentBefore(@Param("before") LocalDateTime before);
}
