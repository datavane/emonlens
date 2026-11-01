package io.emonlens.server.notify;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.NotifyRecordEntity;

import lombok.RequiredArgsConstructor;

/**
 * WEBHOOK 渠道：HMAC-SHA256 签名 v2。签名原文：
 *   timestamp + "\n" + eventId + "\n" + eventType + "\n" + rawBody
 * 其中 eventType 取 header「X-Em-Event-Type」的原始值（如 TEST / INCIDENT_CREATED，
 * body 里的 trust.*.v1 是名字空间化形式，不参与签名）；常量时间验签由接收方执行。
 * headers：X-Em-Timestamp / X-Em-Event-Id / X-Em-Event-Type / X-Em-Key-Version / X-Em-Signature。
 * config: {"url":"...","secret":"...","keyVersion":"v1"}
 *
 * SSRF 防护：仅允许 http/https，解析出的目标 IP 不得落在回环/私有/链路本地地址段
 * （webhook 由工作空间用户配置，平台可能部署在能访问内网/云元数据的位置）。
 */
@Component
@RequiredArgsConstructor
public class WebhookChannel implements NotifyChannel {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmonLensProperties properties;

    @Override
    public String channel() {
        return "WEBHOOK";
    }

    InetSocketAddress assertSafeTarget(URL url) throws IOException {
        if (properties.getNotify().isAllowPrivateWebhookTargets()) {
            // 例外开关（本机集成测试）：仍解析出地址用于连接钉扎，但不做私网校验
            return toSocketAddress(url, InetAddress.getByName(url.getHost()));
        }
        String protocol = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IOException("webhook url scheme not allowed: " + protocol);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(url.getHost());
        } catch (IOException e) {
            throw new IOException("webhook host unresolvable: " + url.getHost());
        }
        if (addresses.length == 0) {
            throw new IOException("webhook host unresolvable: " + url.getHost());
        }
        // 必须校验全部解析结果：恶意域名可能同时返回公网 + 私网地址
        for (InetAddress addr : addresses) {
            if (isForbiddenAddress(addr)) {
                throw new IOException("webhook host is a private/loopback address: " + addr.getHostAddress());
            }
        }
        return toSocketAddress(url, addresses[0]);
    }

    private InetSocketAddress toSocketAddress(URL url, InetAddress address) {
        int port = url.getPort();
        boolean tls = "https".equalsIgnoreCase(url.getProtocol());
        if (port == -1) {
            port = tls ? 443 : 80;
        }
        return new InetSocketAddress(address, port);
    }

    /** 回环/私网/链路本地/组播 + CGNAT 100.64/10 + IPv6 ULA fc00::/7 + IPv4-mapped IPv6。 */
    private boolean isForbiddenAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            if (first == 0) {
                return true;                                       // 0.0.0.0/8
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;                                       // 100.64.0.0/10 CGNAT
            }
            return false;
        }
        if (raw.length == 16) {
            if ((raw[0] & 0xfe) == 0xfc) {
                return true;                                       // fc00::/7 ULA
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff) {
                try {
                    return isForbiddenAddress(InetAddress.getByAddress(
                            new byte[]{raw[12], raw[13], raw[14], raw[15]}));
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String send(NotifyRecordEntity record, String configJson, String subject, String content,
                       Map<String, Object> variables) throws Exception {
        JsonNode config = objectMapper.readTree(configJson == null ? "{}" : configJson);
        String url = config.path("url").asText();
        String secret = config.path("secret").asText();
        if (url.isEmpty() || secret.isEmpty()) {
            throw new IllegalStateException("webhook channel misconfigured: url/secret required");
        }
        URL target = new URL(url);
        // 解析校验与连接使用同一 IP：彻底消除“校验时公网、连接时私网”的 DNS rebinding TOCTOU
        InetSocketAddress pinned = assertSafeTarget(target);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        // eventId 缺失（如「测试推送」直接构造 record）时生成一个：签名原文、header、body
        // 三者必须用同一个值，否则接收方按签名公式永远验不过；空串还会丢掉重放保护
        String eventId = record.getEventId();
        if (eventId == null || eventId.isEmpty()) {
            eventId = java.util.UUID.randomUUID().toString();
            record.setEventId(eventId);
        }
        String body = objectMapper.writeValueAsString(Map.of(
                "event_id", eventId,
                "event_type", "trust." + record.getEventType().toLowerCase().replace('_', '.') + ".v1",
                "workspace_id", record.getWorkspaceId() == null ? "" : record.getWorkspaceId(),
                "occurred_at", record.getCreatedAt() == null ? "" : record.getCreatedAt().toString(),
                "payload", variables));
        String signature = hmac(timestamp + "\n" + eventId + "\n" + record.getEventType()
                + "\n" + body, secret);

        // 禁跟随重定向：重定向目标不经安全校验；钉扎客户端本身不跟随 3xx（仅透出状态码）
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Em-Event-Id", eventId);
        headers.put("X-Em-Event-Type", record.getEventType());
        headers.put("X-Em-Timestamp", timestamp);
        headers.put("X-Em-Key-Version", config.path("keyVersion").asText("v1"));
        headers.put("X-Em-Signature", "hmac-sha256=" + signature);
        int code = postPinned(target, pinned, headers, body.getBytes(StandardCharsets.UTF_8));
        if (code < 200 || code >= 300) {
            throw new IOException("webhook http " + code);
        }
        return url;
    }

    /**
     * 钉扎 HTTP POST：TCP 直连已校验的 IP，Host/SNI/TLS 证书校验仍使用原始域名。
     * 固定 POST + Content-Length + Connection: close；只解析状态码，不跟随重定向。
     */
    private int postPinned(URL target, InetSocketAddress pinned,
                           Map<String, String> headers, byte[] body) throws IOException {
        boolean tls = "https".equalsIgnoreCase(target.getProtocol());
        String host = target.getHost();
        String path = target.getFile() == null || target.getFile().isEmpty() ? "/" : target.getFile();
        String hostHeader = host + ((pinned.getPort() == (tls ? 443 : 80)) ? "" : ":" + pinned.getPort());
        StringBuilder request = new StringBuilder();
        request.append("POST ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeader).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        request.append("Content-Length: ").append(body.length).append("\r\n");
        request.append("Connection: close\r\n\r\n");

        Socket socket = new Socket();
        try {
            socket.connect(pinned, 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            if (tls) {
                SSLSocket tlsSocket = wrapTls(socket, host, pinned.getPort());
                tlsSocket.startHandshake();
                // 握手后按原始域名校验证书（TCP 直连 IP 时 JDK 不会自动做）
                HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
                if (!verifier.verify(host, tlsSocket.getSession())) {
                    throw new IOException("webhook tls hostname verification failed: " + host);
                }
                socket = tlsSocket;
                out = socket.getOutputStream();
                in = socket.getInputStream();
            }
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
            return readStatus(in);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private SSLSocket wrapTls(Socket plain, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(plain, host, port, true);
        SSLParameters params = tls.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(host)));
        tls.setSSLParameters(params);
        return tls;
    }

    /** 读取并解析状态行，随后丢弃至多 64KB 响应体（Connection: close 由服务端收尾）。 */
    private int readStatus(InputStream in) throws IOException {
        StringBuilder statusLine = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                break;
            }
            if (ch != '\r') {
                statusLine.append((char) ch);
            }
            if (statusLine.length() > 512) {
                throw new IOException("webhook response status line too long");
            }
        }
        if (statusLine.length() == 0) {
            throw new IOException("webhook empty response");
        }
        // HTTP/1.1 200 OK
        String[] parts = statusLine.toString().split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("webhook malformed status: " + statusLine);
        }
        try {
            int code = Integer.parseInt(parts[1]);
            int drained = 0;
            byte[] sink = new byte[4096];
            while (drained < 64 * 1024) {
                int n = in.read(sink);
                if (n == -1) {
                    break;
                }
                drained += n;
            }
            return code;
        } catch (NumberFormatException e) {
            throw new IOException("webhook malformed status: " + statusLine);
        }
    }

    static String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}
