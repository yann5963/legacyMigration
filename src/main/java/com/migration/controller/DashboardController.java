package com.migration.controller;

import com.migration.dto.RetryRequest;
import com.migration.model.MigrationFileStatus;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.service.AsyncMigrationRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/migration/files")
@RequiredArgsConstructor
public class DashboardController {

    private final MigrationFileStatusRepository repository;
    private final AsyncMigrationRetryService retryService;

    @GetMapping
    public List<MigrationFileStatus> getAllFiles() {
        return repository.findAll();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retryMigration(@PathVariable Long id, @RequestBody(required = false) RetryRequest request) {
        String customPrompt = (request != null) ? request.getCustomPrompt() : null;
        retryService.retryMigration(id, customPrompt);
        return ResponseEntity.accepted().build();
    }
}
