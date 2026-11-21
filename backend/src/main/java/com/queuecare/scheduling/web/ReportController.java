package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.ReportService;
import com.queuecare.scheduling.application.ReportService.LapsedRow;
import com.queuecare.scheduling.application.ReportService.Summary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** O resumo do PRÓPRIO estúdio (ADR 0031). Só o dono (PROVIDER): tem valores em dinheiro e nome de cliente. Sem cache: o número muda a cada atendimento. */
@RestController
@RequestMapping("/api/provider/reports")
@PreAuthorize("hasRole('PROVIDER')")
class ReportController {

	private final ReportService reports;

	ReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/summary")
	ResponseEntity<Summary> summary(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.summary(UUID.fromString(jwt.getSubject()), from, to));
	}

	@GetMapping("/lapsed")
	ResponseEntity<List<LapsedRow>> lapsed(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "60") int days) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.lapsed(UUID.fromString(jwt.getSubject()), days));
	}
}
