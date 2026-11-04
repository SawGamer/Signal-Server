package org.selfhost.registration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.signal.registration.rpc.ClientType;
import org.signal.registration.rpc.MessageTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends codes through Twilio's REST API (SMS via Messages, voice via Calls). Uses plain HTTP so no Twilio SDK
 * dependency is needed.
 */
final class TwilioSender implements VerificationSender {

  private static final Logger log = LoggerFactory.getLogger(TwilioSender.class);
  private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*(\\d+)");

  private final Config.TwilioConfig config;
  private final String appName;
  private final String smsTemplate;
  private final HttpClient httpClient;
  private final String authorizationHeader;

  TwilioSender(final Config.TwilioConfig config, final String appName, final String smsTemplate,
      final HttpClient httpClient) {
    this.config = config;
    this.appName = appName;
    this.smsTemplate = smsTemplate;
    this.httpClient = httpClient;
    this.authorizationHeader = "Basic " + Base64.getEncoder()
        .encodeToString((config.accountSid() + ":" + config.authToken()).getBytes(StandardCharsets.UTF_8));
  }

  TwilioSender(final Config.TwilioConfig config, final String appName, final String smsTemplate) {
    this(config, appName, smsTemplate, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  @Override
  public void send(final String e164, final String code, final MessageTransport transport,
      final ClientType clientType, final String acceptLanguage) throws SenderException {

    final Map<String, String> form = new LinkedHashMap<>();
    form.put("To", e164);

    final String resource;
    if (transport == MessageTransport.MESSAGE_TRANSPORT_VOICE) {
      if (config.fromNumber() == null || config.fromNumber().isBlank()) {
        throw new SenderException(Failure.TRANSPORT_NOT_ALLOWED, "voice requires TWILIO_FROM_NUMBER");
      }
      resource = "Calls.json";
      form.put("From", config.fromNumber());
      form.put("Twiml", voiceTwiml(code));
    } else {
      resource = "Messages.json";
      if (config.messagingServiceSid() != null && !config.messagingServiceSid().isBlank()) {
        form.put("MessagingServiceSid", config.messagingServiceSid());
      } else {
        form.put("From", config.fromNumber());
      }
      form.put("Body", renderSms(smsTemplate, appName, code));
    }

    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(config.apiBaseUrl() + "/2010-04-01/Accounts/" + config.accountSid() + "/" + resource))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", authorizationHeader)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
        .build();

    final HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (final IOException e) {
      throw new SenderException(Failure.UNAVAILABLE, "Twilio unreachable: " + e.getMessage());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SenderException(Failure.UNAVAILABLE, "interrupted");
    }

    if (response.statusCode() / 100 == 2) {
      return;
    }

    final int twilioCode = parseErrorCode(response.body());
    log.warn("Twilio returned HTTP {} (error {}) sending {} to {}", response.statusCode(), twilioCode, transport,
        Redact.e164(e164));
    throw new SenderException(classify(response.statusCode(), twilioCode), "Twilio error " + twilioCode);
  }

  static String renderSms(final String template, final String appName, final String code) {
    return template.replace("{app}", appName).replace("{code}", code);
  }

  /** Reads digits one at a time, twice, so they're intelligible over a phone line. */
  static String voiceTwiml(final String code) {
    final String spaced = code.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(", "));
    return "<Response><Say>Your verification code is: " + spaced + ". Again, your code is: " + spaced
        + ".</Say></Response>";
  }

  /**
   * See https://www.twilio.com/docs/api/errors
   */
  static Failure classify(final int httpStatus, final int twilioCode) {
    return switch (twilioCode) {
      case 21211, 21214, 21217, 21401, 21407, 21614 -> Failure.ILLEGAL_ARGUMENT; // invalid / non-mobile number
      case 21610, 30004, 30007 -> Failure.REJECTED;                             // opted out, blocked, carrier-filtered
      case 21612 -> Failure.TRANSPORT_NOT_ALLOWED;                              // number not reachable via SMS
      case 21215, 21408 -> Failure.UNAVAILABLE;                                 // your Twilio geo-permissions block it
      default -> httpStatus == 429 || httpStatus >= 500 ? Failure.UNAVAILABLE : Failure.REJECTED;
    };
  }

  static int parseErrorCode(final String body) {
    if (body == null) {
      return 0;
    }
    final Matcher matcher = ERROR_CODE.matcher(body);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private static String encodeForm(final Map<String, String> form) {
    return form.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
