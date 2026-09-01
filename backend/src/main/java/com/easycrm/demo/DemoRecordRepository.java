package com.easycrm.demo;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoRecordRepository extends JpaRepository<DemoRecord, UUID> {}
