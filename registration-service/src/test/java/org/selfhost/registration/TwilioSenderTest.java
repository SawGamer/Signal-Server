package org.selfhost.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.rpc.ClientType;
import org.signal.registration.rpc.MessageTransport;

class TwilioSenderTest {

  private HttpServer fakeTwilio;
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<Map<String, String>> lastForm = new AtomicReference<>();
  private volatile int status = 201;
  private volatile String responseBody = "{\"sid\":\"SM123\"}";

  @BeforeEach
  void setUp() throws Exception {
    fakeTwilio = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeTwilio.createContext("/", exchange -> {
      lastPath.set(exchange.getRequestURI().getPath());
      lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      lastForm.set(Arrays.stream(body.split("&"))
          .map(kv -> kv.split("=", 2))
          .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8))));
      final byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    fakeTwilio.start();
  }

  @AfterEach
  void tearDown() {
    fakeTwilio.stop(0);
  }

  private TwilioSender sender(final String from, final String messagingServiceSid) {
    return new TwilioSender(new Config.TwilioConfig("AC123", "tok", from, messagingServiceSid,
        "http://127.0.0.1:" + fakeTwilio.getAddress().getPort()), "Acme Chat", Config.DEFAULT_SMS_TEMPLATE);
  }

  @Test
  void sendsSms() throws Exception {
    sender("+15550001111", null).send("+4915123456789", "123456", MessageTransport.MESSAGE_TRANSPORT_SMS,
        ClientType.CLIENT_TYPE_IOS, "en");

    assertEquals("/2010-04-01/Accounts/AC123/Messages.json", lastPath.get());
    assertEquals("Basic " + Base64.getEncoder().encodeToString("AC123:tok".getBytes(StandardCharsets.UTF_8)),
        lastAuth.get());
    assertEquals("+4915123456789", lastForm.get().get("To"));
    assertEquals("+15550001111", lastForm.get().get("From"));
    assertEquals("Your Acme Chat code: 123456\n\nDo not share this code with anyone.", lastForm.get().get("Body"));
  }

  @Test
  void prefersMessagingService() throws Exception {
    sender("+15550001111", "MG999").send("+4915123456789", "123456", MessageTransport.MESSAGE_TRANSPORT_SMS,
        ClientType.CLIENT_TYPE_IOS, "en");
    assertEquals("MG999", lastForm.get().get("MessagingServiceSid"));
    assertTrue(!lastForm.get().containsKey("From"));
  }

  @Test
  void placesVoiceCall() throws Exception {
    sender("+15550001111", null).send("+4915123456789", "123456", MessageTransport.MESSAGE_TRANSPORT_VOICE,
        ClientType.CLIENT_TYPE_IOS, "en");
    assertEquals("/2010-04-01/Accounts/AC123/Calls.json", lastPath.get());
    assertTrue(lastForm.get().get("Twiml").contains("1, 2, 3, 4, 5, 6"));
  }

  @Test
  void voiceWithoutFromNumberIsNotAllowed() {
    final VerificationSender.SenderException e = assertThrows(VerificationSender.SenderException.class,
        () -> sender(null, "MG999").send("+4915123456789", "123456", MessageTransport.MESSAGE_TRANSPORT_VOICE,
            ClientType.CLIENT_TYPE_IOS, "en"));
    assertEquals(VerificationSender.Failure.TRANSPORT_NOT_ALLOWED, e.failure());
  }

  @Test
  void mapsTwilioErrors() {
    status = 400;
    responseBody = "{\"code\": 21211, \"message\": \"Invalid 'To' Phone Number\", \"status\": 400}";
    final VerificationSender.SenderException e = assertThrows(VerificationSender.SenderException.class,
        () -> sender("+15550001111", null).send("+1", "123456", MessageTransport.MESSAGE_TRANSPORT_SMS,
            ClientType.CLIENT_TYPE_IOS, "en"));
    assertEquals(VerificationSender.Failure.ILLEGAL_ARGUMENT, e.failure());

    assertEquals(VerificationSender.Failure.REJECTED, TwilioSender.classify(400, 21610));
    assertEquals(VerificationSender.Failure.UNAVAILABLE, TwilioSender.classify(400, 21408));
    assertEquals(VerificationSender.Failure.UNAVAILABLE, TwilioSender.classify(503, 0));
    assertEquals(VerificationSender.Failure.UNAVAILABLE, TwilioSender.classify(429, 20429));
  }

  @Test
  void parsesTestNumbers() {
    assertEquals(Map.of("+15555550100", "123456", "+15555550101", "654321"),
        Config.parseTestNumbers("+15555550100=123456, +15555550101=654321"));
    assertThrows(IllegalArgumentException.class, () -> Config.parseTestNumbers("15555550100=123456"));
    assertThrows(IllegalArgumentException.class, () -> Config.parseTestNumbers("+15555550100=12345"));
  }
}
