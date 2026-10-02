package com.praxedo.securefiles.config;

import java.lang.management.ManagementFactory;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.praxedo.securefiles.application.common.port.out.TransactionRunner;
import com.praxedo.securefiles.application.file.model.WorkerSettings;
import com.praxedo.securefiles.application.file.port.in.DownloadFileUseCase;
import com.praxedo.securefiles.application.file.port.in.MaintainFilesUseCase;
import com.praxedo.securefiles.application.file.port.in.MonitorFilesUseCase;
import com.praxedo.securefiles.application.file.port.in.QueryFilesUseCase;
import com.praxedo.securefiles.application.file.port.in.ScanFilesUseCase;
import com.praxedo.securefiles.application.file.port.in.UploadFileUseCase;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.application.file.port.out.DownloadAudit;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.port.out.OperationalReadings;
import com.praxedo.securefiles.application.file.port.out.QuarantineWriter;
import com.praxedo.securefiles.application.file.port.out.ServableReader;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.application.file.service.FileDownloadService;
import com.praxedo.securefiles.application.file.service.FileMaintenanceService;
import com.praxedo.securefiles.application.file.service.FileMonitoringService;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.application.file.service.FileQueryService;
import com.praxedo.securefiles.application.file.service.FileScanService;
import com.praxedo.securefiles.application.file.service.QuarantineSweeper;
import com.praxedo.securefiles.application.file.service.UploadFileService;
import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore;

/**
 * Where the hexagon is assembled — and the only place that knows the services.
 *
 * <p>Each use case is published <strong>as its port in</strong>: a controller,
 * a scheduler or a metrics binder asks for {@link UploadFileUseCase}, never for
 * {@link UploadFileService}. Each service receives its <strong>ports
 * out</strong>, implemented by the driven adapters Spring found. The
 * application layer is not allowed to import a framework, so it cannot
 * annotate itself into existence: wiring is a composition concern, and it is
 * visible in one file.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceProperties.class)
class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // ── ports in: what the driving adapters may call ────────────────────

    @Bean
    UploadFileUseCase uploadFileUseCase(FileCatalog catalog, QuarantineWriter quarantine,
                                        IdempotencyStore idempotency, TransactionRunner transactions,
                                        ServiceProperties properties, Clock clock) {
        return new UploadFileService(catalog, quarantine, idempotency, transactions, properties.upload(), clock);
    }

    @Bean
    QueryFilesUseCase queryFilesUseCase(FileCatalog catalog) {
        return new FileQueryService(catalog);
    }

    @Bean
    DownloadFileUseCase downloadFileUseCase(FileCatalog catalog, ServableReader servable, DownloadAudit audit) {
        return new FileDownloadService(catalog, servable, audit);
    }

    /** Named after the process and host, so a lease in the database says who holds it. */
    @Bean
    ScanFilesUseCase scanFilesUseCase(FileWorkQueue queue, WorkerStorage storage, AntivirusScanner scanner,
                                      FilePromotionService promotion, WorkerSettings settings, Clock clock) {
        return new FileScanService(queue, storage, scanner, promotion, settings, clock,
                ManagementFactory.getRuntimeMXBean().getName());
    }

    @Bean
    MaintainFilesUseCase maintainFilesUseCase(FileWorkQueue queue, WorkerStorage storage, FileCatalog catalog,
                                              IdempotencyStore idempotency, WorkerSettings settings,
                                              ServiceProperties properties, Clock clock) {
        ServiceProperties.Maintenance maintenance = properties.maintenance();
        QuarantineSweeper sweeper = new QuarantineSweeper(storage, catalog, clock,
                maintenance.orphanAge(), maintenance.sweepBatch());
        return new FileMaintenanceService(queue, sweeper, idempotency, settings, maintenance.abandonedUploadAfter());
    }

    @Bean
    MonitorFilesUseCase monitorFilesUseCase(FileCatalog catalog, OperationalReadings readings) {
        return new FileMonitoringService(catalog, readings);
    }

    // ── internal collaborators of the services ──────────────────────────

    @Bean
    WorkerSettings workerSettings(ServiceProperties properties) {
        return properties.worker();
    }

    /** Used by the scan service; not a use case — nothing outside the core triggers a promotion. */
    @Bean
    FilePromotionService filePromotionService(FileWorkQueue queue, WorkerStorage storage, WorkerSettings settings,
                                              Clock clock) {
        return new FilePromotionService(queue, storage, settings, clock);
    }
}
