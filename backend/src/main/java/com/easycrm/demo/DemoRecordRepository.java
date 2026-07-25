package com.easycrm.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DemoRecordRepository extends JpaRepository<DemoRecord, UUID> {}
