package com.easycrm.sales;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, UUID> {

    // PESSIMISTIC_WRITE → SELECT ... FOR UPDATE. Serializes concurrent sends within a
    // tenant/FY so the sequence is gapless. Runs inside the caller's send transaction, which
    // sets the RLS tenant GUC (challenge #8).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DocumentCounter c where c.docType = :docType and c.fy = :fy")
    Optional<DocumentCounter> findForUpdate(@Param("docType") String docType, @Param("fy") String fy);
}
