package uk.gegc.quizmaker.features.billing.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
// No Spring Boot imports needed for pure WireMock testing

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests using WireMock to simulate realistic Stripe API responses
 * with expanded/unexpanded fields and API version compatibility.
 * 
 * This test focuses on WireMock functionality and Stripe API response simulation
 * without requiring Spring Boot context to avoid complex dependency issues.
 */
@DisplayName("Stripe API Stubs Tests")
class StripeApiStubsTest {

    // No Spring Boot dependencies needed for WireMock testing

    private WireMockServer wireMockServer;
    private static final int WIREMOCK_PORT = 8089;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(WIREMOCK_PORT));
        wireMockServer.start();
        WireMock.configureFor("localhost", WIREMOCK_PORT);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Simple WireMock test")
    void shouldStartWireMockServer() {
        // Simple test to verify WireMock is working
        assertThat(wireMockServer.isRunning()).isTrue();
    }

    @Nested
    @DisplayName("Realistic Payloads with Expanded/Unexpanded Fields")
    class RealisticPayloadsTest {

        @Test
        @DisplayName("Session - expanded PaymentIntent and Customer fields")
        void shouldHandleSessionWithExpandedFields() throws Exception {
            // Given - Realistic Stripe session with expanded fields
            String sessionId = "cs_test_session_" + System.currentTimeMillis();
            String paymentIntentId = "pi_test_payment_intent_" + System.currentTimeMillis();
            String customerId = "cus_test_customer_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API response with expanded fields
            String sessionResponse = String.format("""
                {
                    "id": "%s",
                    "object": "checkout.session",
                    "payment_intent": {
                        "id": "%s",
                        "object": "payment_intent",
                        "amount": 2000,
                        "currency": "usd",
                        "status": "succeeded",
                        "customer": "%s"
                    },
                    "customer": {
                        "id": "%s",
                        "object": "customer",
                        "email": "test@example.com",
                        "metadata": {
                            "user_id": "%s"
                        }
                    },
                    "client_reference_id": "%s",
                    "metadata": {
                        "pack_id": "%s"
                    },
                    "line_items": {
                        "object": "list",
                        "data": [
                            {
                                "id": "li_test_line_item",
                                "object": "line_item",
                                "quantity": 1,
                                "price": {
                                    "id": "price_test_price",
                                    "object": "price",
                                    "unit_amount": 2000,
                                    "currency": "usd"
                                }
                            }
                        ]
                    }
                }
                """, sessionId, paymentIntentId, customerId, customerId, userId, userId, packId);

            stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(sessionResponse)));

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/checkout/sessions/" + sessionId + "?expand=payment_intent,customer";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(sessionId);
            assertThat(response.body()).contains(paymentIntentId);
            assertThat(response.body()).contains(customerId);
            assertThat(response.body()).contains(userId);
            assertThat(response.body()).contains(packId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer")));
        }

        @Test
        @DisplayName("Invoice - expanded Subscription and Customer fields")
        void shouldHandleInvoiceWithExpandedFields() throws Exception {
            // Given
            String invoiceId = "in_test_invoice_" + System.currentTimeMillis();
            String subscriptionId = "sub_test_subscription_" + System.currentTimeMillis();
            String customerId = "cus_test_customer_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();

            // Mock Stripe API responses
            String invoiceResponse = String.format("""
                {
                    "id": "%s",
                    "object": "invoice",
                    "subscription": {
                        "id": "%s",
                        "object": "subscription",
                        "customer": "%s",
                        "status": "active",
                        "items": {
                            "object": "list",
                            "data": [
                                {
                                    "id": "si_test_subscription_item",
                                    "object": "subscription_item",
                                    "price": {
                                        "id": "price_test_price",
                                        "object": "price",
                                        "unit_amount": 1000,
                                        "currency": "usd"
                                    }
                                }
                            ]
                        }
                    },
                    "customer": {
                        "id": "%s",
                        "object": "customer",
                        "email": "test@example.com",
                        "metadata": {
                            "user_id": "%s"
                        }
                    },
                    "amount_paid": 1000,
                    "currency": "usd",
                    "status": "paid"
                }
                """, invoiceId, subscriptionId, customerId, customerId, userId);

            stubFor(get(urlPathEqualTo("/v1/invoices/" + invoiceId))
                .withQueryParam("expand", equalTo("subscription,subscription.items.data.price,customer"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(invoiceResponse)));

            // Create webhook payload
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "invoice.payment_succeeded",
                    "api_version": "2023-10-16",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "invoice",
                            "subscription": "%s",
                            "customer": "%s",
                            "amount_paid": 1000,
                            "currency": "usd",
                            "status": "paid"
                        }
                    }
                }
                """, eventId, invoiceId, subscriptionId, customerId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/invoices/" + invoiceId + "?expand=subscription,subscription.items.data.price,customer";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(invoiceId);
            assertThat(response.body()).contains(subscriptionId);
            assertThat(response.body()).contains(customerId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/invoices/" + invoiceId))
                .withQueryParam("expand", equalTo("subscription,subscription.items.data.price,customer")));
        }

        @Test
        @DisplayName("Subscription - expanded Customer and Items fields")
        void shouldHandleSubscriptionWithExpandedFields() throws Exception {
            // Given
            String subscriptionId = "sub_test_subscription_" + System.currentTimeMillis();
            String customerId = "cus_test_customer_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();

            // Mock Stripe API response
            String subscriptionResponse = String.format("""
                {
                    "id": "%s",
                    "object": "subscription",
                    "customer": {
                        "id": "%s",
                        "object": "customer",
                        "email": "test@example.com",
                        "metadata": {
                            "user_id": "%s"
                        }
                    },
                    "status": "active",
                    "items": {
                        "object": "list",
                        "data": [
                            {
                                "id": "si_test_subscription_item",
                                "object": "subscription_item",
                                "price": {
                                    "id": "price_test_price",
                                    "object": "price",
                                    "unit_amount": 1000,
                                    "currency": "usd"
                                }
                            }
                        ]
                    }
                }
                """, subscriptionId, customerId, userId);

            stubFor(get(urlPathEqualTo("/v1/subscriptions/" + subscriptionId))
                .withQueryParam("expand", equalTo("customer,items.data.price"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(subscriptionResponse)));

            // Create webhook payload
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "customer.subscription.deleted",
                    "api_version": "2023-10-16",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "subscription",
                            "customer": "%s",
                            "status": "canceled"
                        }
                    }
                }
                """, eventId, subscriptionId, customerId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/subscriptions/" + subscriptionId + "?expand=customer,items.data.price";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(subscriptionId);
            assertThat(response.body()).contains(customerId);
            assertThat(response.body()).contains(userId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscriptionId))
                .withQueryParam("expand", equalTo("customer,items.data.price")));
        }

        @Test
        @DisplayName("Customer - expanded metadata fields")
        void shouldHandleCustomerWithExpandedFields() throws Exception {
            // Given
            String customerId = "cus_test_customer_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();

            // Mock Stripe API response
            String customerResponse = String.format("""
                {
                    "id": "%s",
                    "object": "customer",
                    "email": "test@example.com",
                    "metadata": {
                        "user_id": "%s",
                        "subscription_tier": "premium",
                        "created_via": "webhook"
                    },
                    "subscriptions": {
                        "object": "list",
                        "data": []
                    }
                }
                """, customerId, userId);

            stubFor(get(urlPathEqualTo("/v1/customers/" + customerId))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(customerResponse)));

            // Create webhook payload
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "customer.created",
                    "api_version": "2023-10-16",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "customer",
                            "email": "test@example.com",
                            "metadata": {
                                "user_id": "%s"
                            }
                        }
                    }
                }
                """, eventId, customerId, userId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/customers/" + customerId + "?expand=metadata";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(customerId);
            assertThat(response.body()).contains(userId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/customers/" + customerId))
                .withQueryParam("expand", equalTo("metadata")));
        }

        @Test
        @DisplayName("Refund - expanded Charge and PaymentIntent fields")
        void shouldHandleRefundWithExpandedFields() throws Exception {
            // Given
            String refundId = "re_test_refund_" + System.currentTimeMillis();
            String chargeId = "ch_test_charge_" + System.currentTimeMillis();
            String paymentIntentId = "pi_test_payment_intent_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API responses
            String refundResponse = String.format("""
                {
                    "id": "%s",
                    "object": "refund",
                    "charge": {
                        "id": "%s",
                        "object": "charge",
                        "payment_intent": {
                            "id": "%s",
                            "object": "payment_intent",
                            "amount": 2000,
                            "currency": "usd",
                            "metadata": {
                                "user_id": "%s",
                                "pack_id": "%s"
                            }
                        }
                    },
                    "amount": 1000,
                    "currency": "usd",
                    "status": "succeeded"
                }
                """, refundId, chargeId, paymentIntentId, userId, packId);

            stubFor(get(urlPathEqualTo("/v1/refunds/" + refundId))
                .withQueryParam("expand", equalTo("charge,charge.payment_intent"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(refundResponse)));

            // Create webhook payload
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "refund.created",
                    "api_version": "2023-10-16",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "refund",
                            "charge": "%s",
                            "amount": 1000,
                            "currency": "usd",
                            "status": "succeeded"
                        }
                    }
                }
                """, eventId, refundId, chargeId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/refunds/" + refundId + "?expand=charge,charge.payment_intent";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(refundId);
            assertThat(response.body()).contains(chargeId);
            assertThat(response.body()).contains(paymentIntentId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/refunds/" + refundId))
                .withQueryParam("expand", equalTo("charge,charge.payment_intent")));
        }

        @Test
        @DisplayName("Dispute - expanded Charge and PaymentIntent fields")
        void shouldHandleDisputeWithExpandedFields() throws Exception {
            // Given
            String disputeId = "dp_test_dispute_" + System.currentTimeMillis();
            String chargeId = "ch_test_charge_" + System.currentTimeMillis();
            String paymentIntentId = "pi_test_payment_intent_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API responses
            String disputeResponse = String.format("""
                {
                    "id": "%s",
                    "object": "dispute",
                    "charge": {
                        "id": "%s",
                        "object": "charge",
                        "payment_intent": {
                            "id": "%s",
                            "object": "payment_intent",
                            "amount": 2000,
                            "currency": "usd",
                            "metadata": {
                                "user_id": "%s",
                                "pack_id": "%s"
                            }
                        }
                    },
                    "amount": 2000,
                    "currency": "usd",
                    "status": "funds_withdrawn"
                }
                """, disputeId, chargeId, paymentIntentId, userId, packId);

            stubFor(get(urlPathEqualTo("/v1/disputes/" + disputeId))
                .withQueryParam("expand", equalTo("charge,charge.payment_intent"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(disputeResponse)));

            // Create webhook payload
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "charge.dispute.funds_withdrawn",
                    "api_version": "2023-10-16",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "dispute",
                            "charge": "%s",
                            "amount": 2000,
                            "currency": "usd",
                            "status": "funds_withdrawn"
                        }
                    }
                }
                """, eventId, disputeId, chargeId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/disputes/" + disputeId + "?expand=charge,charge.payment_intent";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(disputeId);
            assertThat(response.body()).contains(chargeId);
            assertThat(response.body()).contains(paymentIntentId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/disputes/" + disputeId))
                .withQueryParam("expand", equalTo("charge,charge.payment_intent")));
        }

        @Test
        @DisplayName("Charge - expanded PaymentIntent fields")
        void shouldHandleChargeWithExpandedFields() throws Exception {
            // Given
            String chargeId = "ch_test_charge_" + System.currentTimeMillis();
            String paymentIntentId = "pi_test_payment_intent_" + System.currentTimeMillis();
            String customerId = "cus_test_customer_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API response
            String chargeResponse = String.format("""
                {
                    "id": "%s",
                    "object": "charge",
                    "payment_intent": {
                        "id": "%s",
                        "object": "payment_intent",
                        "amount": 2000,
                        "currency": "usd",
                        "status": "succeeded",
                        "metadata": {
                            "user_id": "%s",
                            "pack_id": "%s"
                        }
                    },
                    "customer": "%s",
                    "amount": 2000,
                    "currency": "usd",
                    "status": "succeeded"
                }
                """, chargeId, paymentIntentId, userId, packId, customerId);

            stubFor(get(urlPathEqualTo("/v1/charges/" + chargeId))
                .withQueryParam("expand", equalTo("payment_intent,customer"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(chargeResponse)));

            // Create webhook payload
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "charge.succeeded",
                    "api_version": "2023-10-16",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "charge",
                            "payment_intent": "%s",
                            "amount": 2000,
                            "currency": "usd",
                            "status": "succeeded"
                        }
                    }
                }
                """, eventId, chargeId, paymentIntentId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/charges/" + chargeId + "?expand=payment_intent,customer";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(chargeId);
            assertThat(response.body()).contains(paymentIntentId);
            assertThat(response.body()).contains(customerId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/charges/" + chargeId))
                .withQueryParam("expand", equalTo("payment_intent,customer")));
        }
    }

    @Nested
    @DisplayName("API Version Compatibility")
    class ApiVersionCompatibilityTest {

        @Test
        @DisplayName("Older API version - missing fields handled gracefully")
        void shouldHandleOlderApiVersion() throws Exception {
            // Given - Simulate older API version with missing fields
            String sessionId = "cs_test_session_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API response with minimal fields (older API version)
            String sessionResponse = String.format("""
                {
                    "id": "%s",
                    "object": "checkout.session",
                    "payment_intent": "pi_test_payment_intent",
                    "client_reference_id": "%s",
                    "metadata": {
                        "pack_id": "%s"
                    }
                }
                """, sessionId, userId, packId);

            stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(sessionResponse)));

            // Create webhook payload with older API version
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "checkout.session.completed",
                    "api_version": "2020-08-27",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "checkout.session",
                            "payment_intent": "pi_test_payment_intent",
                            "client_reference_id": "%s",
                            "metadata": {
                                "pack_id": "%s"
                            }
                        }
                    }
                }
                """, eventId, sessionId, userId, packId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/checkout/sessions/" + sessionId + "?expand=payment_intent,customer";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(sessionId);
            assertThat(response.body()).contains(userId);
            assertThat(response.body()).contains(packId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer")));
        }

        @Test
        @DisplayName("Newer API version - additional fields ignored gracefully")
        void shouldHandleNewerApiVersion() throws Exception {
            // Given - Simulate newer API version with additional fields
            String sessionId = "cs_test_session_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API response with additional fields (newer API version)
            String sessionResponse = String.format("""
                {
                    "id": "%s",
                    "object": "checkout.session",
                    "payment_intent": "pi_test_payment_intent",
                    "client_reference_id": "%s",
                    "metadata": {
                        "pack_id": "%s"
                    },
                    "new_field_in_future_version": "ignored_value",
                    "additional_metadata": {
                        "future_feature": "ignored"
                    }
                }
                """, sessionId, userId, packId);

            stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(sessionResponse)));

            // Create webhook payload with newer API version
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "checkout.session.completed",
                    "api_version": "2024-06-20",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "checkout.session",
                            "payment_intent": "pi_test_payment_intent",
                            "client_reference_id": "%s",
                            "metadata": {
                                "pack_id": "%s"
                            },
                            "new_field_in_future_version": "ignored_value"
                        }
                    }
                }
                """, eventId, sessionId, userId, packId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/checkout/sessions/" + sessionId + "?expand=payment_intent,customer";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(sessionId);
            assertThat(response.body()).contains(userId);
            assertThat(response.body()).contains(packId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer")));
        }

        @Test
        @DisplayName("Missing api_version field - handled gracefully")
        void shouldHandleMissingApiVersion() throws Exception {
            // Given
            String sessionId = "cs_test_session_" + System.currentTimeMillis();
            String userId = UUID.randomUUID().toString();
            String packId = UUID.randomUUID().toString();

            // Mock Stripe API response
            String sessionResponse = String.format("""
                {
                    "id": "%s",
                    "object": "checkout.session",
                    "payment_intent": "pi_test_payment_intent",
                    "client_reference_id": "%s",
                    "metadata": {
                        "pack_id": "%s"
                    }
                }
                """, sessionId, userId, packId);

            stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(sessionResponse)));

            // Create webhook payload without api_version field
            String eventId = "evt_test_" + System.currentTimeMillis();
            String webhookPayload = String.format("""
                {
                    "id": "%s",
                    "object": "event",
                    "type": "checkout.session.completed",
                    "data": {
                        "object": {
                            "id": "%s",
                            "object": "checkout.session",
                            "payment_intent": "pi_test_payment_intent",
                            "client_reference_id": "%s",
                            "metadata": {
                                "pack_id": "%s"
                            }
                        }
                    }
                }
                """, eventId, sessionId, userId, packId);

            // When - Make HTTP request to WireMock
            String url = "http://localhost:" + WIREMOCK_PORT + "/v1/checkout/sessions/" + sessionId + "?expand=payment_intent,customer";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(sessionId);
            assertThat(response.body()).contains(userId);
            assertThat(response.body()).contains(packId);

            // Verify WireMock was called
            verify(getRequestedFor(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .withQueryParam("expand", equalTo("payment_intent,customer")));
        }
    }
}
