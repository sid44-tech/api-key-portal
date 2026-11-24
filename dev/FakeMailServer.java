import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mail server that accepts every message and keeps it instead of delivering it,
 * so the portal's emails (invitations, expiry reminders, replacement notices) can
 * be read without a real mail account or internet access.
 * <p>
 * Run it straight from source, no build needed:
 * <pre>
 *   java dev/FakeMailServer.java                       # port 2525, saves to ./fake-mail
 *   java dev/FakeMailServer.java --port 2526 --dir /tmp/mail
 * </pre>
 * and start the portal with
 * <pre>
 *   SPRING_MAIL_HOST=localhost
 *   SPRING_MAIL_PORT=2525
 * </pre>
 * Plain SMTP only: no TLS and no sign-in, so leave the mail username, password and
 * STARTTLS settings unset. Each message is saved as a numbered {@code .eml} file
 * in the directory and announced with one line naming its recipients and subject.
 */
public class FakeMailServer {

    private final Path directory;
    private final AtomicInteger received = new AtomicInteger();

    FakeMailServer(Path directory) {
        this.directory = directory;
    }

    public static void main(String[] args) throws IOException {
        int port = 2525;
        Path directory = Path.of("fake-mail");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--dir" -> directory = Path.of(args[++i]);
                default -> {
                    System.err.println("Usage: java dev/FakeMailServer.java [--port 2525] [--dir fake-mail]");
                    System.exit(2);
                }
            }
        }
        Files.createDirectories(directory);
        FakeMailServer server = new FakeMailServer(directory);
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new java.net.InetSocketAddress("localhost", port));
            log("Fake mail server listening on localhost:" + port + ", saving messages to "
                    + directory.toAbsolutePath());
            while (true) {
                Socket socket = listener.accept();
                Thread.ofVirtual().start(() -> server.converse(socket));
            }
        }
    }

    /** One SMTP session: as many messages as the client sends before QUIT. */
    private void converse(Socket socket) {
        try (socket;
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream()) {
            reply(out, "220 localhost fake mail server ready");
            List<String> recipients = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
                switch (command) {
                    case "EHLO" -> reply(out, "250-localhost\r\n250-8BITMIME\r\n250 SMTPUTF8");
                    case "HELO" -> reply(out, "250 localhost");
                    case "MAIL" -> {
                        recipients.clear();
                        reply(out, "250 OK");
                    }
                    case "RCPT" -> {
                        recipients.add(address(line));
                        reply(out, "250 OK");
                    }
                    case "DATA" -> {
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        int number = save(readMessage(in), recipients);
                        reply(out, "250 OK: saved as message " + number);
                        recipients.clear();
                    }
                    case "RSET" -> {
                        recipients.clear();
                        reply(out, "250 OK");
                    }
                    case "NOOP" -> reply(out, "250 OK");
                    case "QUIT" -> {
                        reply(out, "221 Bye");
                        return;
                    }
                    default -> reply(out, "502 Command not implemented");
                }
            }
        } catch (IOException e) {
            log("Connection ended early: " + e.getMessage());
        }
    }

    /** The message text up to the lone dot, with SMTP's dot-stuffing undone. */
    private static String readMessage(BufferedReader in) throws IOException {
        StringBuilder message = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !line.equals(".")) {
            message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
        }
        return message.toString();
    }

    private int save(String message, List<String> recipients) throws IOException {
        int number = received.incrementAndGet();
        Path file = directory.resolve(String.format("%04d.eml", number));
        Files.writeString(file, message, StandardCharsets.UTF_8);
        log("Message " + number + " to " + String.join(", ", recipients) + ": " + subject(message)
                + " (saved as " + file.getFileName() + ")");
        return number;
    }

    /** The Subject header as sent; a subject with non-ASCII characters stays in its encoded form. */
    private static String subject(String message) {
        for (String line : message.split("\r\n")) {
            if (line.isEmpty()) {
                break;
            }
            if (line.regionMatches(true, 0, "Subject:", 0, 8)) {
                return line.substring(8).trim();
            }
        }
        return "(no subject)";
    }

    private static String address(String line) {
        int open = line.indexOf('<');
        int close = line.indexOf('>', open + 1);
        return open >= 0 && close > open ? line.substring(open + 1, close) : line.substring(line.indexOf(':') + 1).trim();
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void log(String line) {
        System.out.println(Instant.now().truncatedTo(ChronoUnit.MILLIS) + " " + line);
    }
}
