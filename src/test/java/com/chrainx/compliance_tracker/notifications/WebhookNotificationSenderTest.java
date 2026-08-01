package com.chrainx.compliance_tracker.notifications;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A real local HTTP server (com.sun.net.httpserver, part of the JDK - no new test dependency
// needed), not a mocked RestClient - same "prefer a real protocol implementation over mocking a
// client library" idiom this project already uses for email (GreenMail, a real local SMTP
// server, in EmailNotificationSenderIntegrationTest). Genuinely proves what request
// WebhookNotificationSender actually sends over the wire.
class WebhookNotificationSenderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void send_postsARealJsonBody_toTheConfiguredWebhookUrl() throws IOException {
        CompletableFuture<String> capturedBody = new CompletableFuture<>();
        CompletableFuture<String> capturedContentType = new CompletableFuture<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            capturedContentType.complete(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        WebhookNotificationSender sender = new WebhookNotificationSender(
                "http://localhost:" + server.getAddress().getPort() + "/webhook");

        Business business = new Business();
        business.setName("Test Cafe Pte Ltd");
        DeadlineRecord record = new DeadlineRecord();
        record.setObligationType(ObligationType.GST_F5);
        record.setDueDate(LocalDate.of(2026, 10, 30));

        sender.send(business, record);

        String body = capturedBody.join();
        assertTrue(body.contains("Test Cafe Pte Ltd"));
        assertTrue(body.contains("GST F5 Filing"));
        assertTrue(body.contains("2026-10-30"));
        assertTrue(capturedContentType.join().startsWith("application/json"));
    }

    @Test
    void send_usesTheCustomObligationsRealCustomName_notTheBareEnumValue() throws IOException {
        CompletableFuture<String> capturedBody = new CompletableFuture<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            capturedBody.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        WebhookNotificationSender sender = new WebhookNotificationSender(
                "http://localhost:" + server.getAddress().getPort() + "/webhook");

        Business business = new Business();
        business.setName("Test Co");
        DeadlineRecord record = new DeadlineRecord();
        record.setObligationType(ObligationType.CUSTOM);
        record.setCustomName("Renew business insurance");
        record.setDueDate(LocalDate.of(2026, 9, 1));

        sender.send(business, record);

        assertTrue(capturedBody.join().contains("Renew business insurance"));
    }

    @Test
    void send_throws_whenTheWebhookEndpointReturnsAnError() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        WebhookNotificationSender sender = new WebhookNotificationSender(
                "http://localhost:" + server.getAddress().getPort() + "/webhook");

        Business business = new Business();
        business.setName("Test Co");
        DeadlineRecord record = new DeadlineRecord();
        record.setObligationType(ObligationType.ACRA_ANNUAL_RETURN);
        record.setDueDate(LocalDate.of(2026, 12, 31));

        // ReminderWorkerService's own per-message try/catch relies on exactly this - a failed
        // send must throw, not swallow the error, so the message is left in the queue for retry.
        assertThrows(Exception.class, () -> sender.send(business, record));
    }

    @Test
    void constructor_throws_whenWebhookUrlIsBlank() {
        // Mirrors the real default (@Value("${notifications.webhook-url:}")) - an unset
        // property resolves to an empty string, not null, when using the ":" default syntax.
        assertThrows(IllegalStateException.class, () -> new WebhookNotificationSender(""));
    }
}
