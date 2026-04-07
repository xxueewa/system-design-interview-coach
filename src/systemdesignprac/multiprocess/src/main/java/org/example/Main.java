package org.example;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Demonstrates the most common inter-process communication (IPC) mechanisms in Java:
 *
 *  1. Standard I/O pipes  – parent writes to child's stdin; child reads and replies via stdout.
 *  2. File-based IPC      – parent writes to a temp file; child reads and appends a reply.
 *  3. Socket (TCP)        – parent spawns a server process, then acts as client and exchanges messages.
 *
 * Each demo spawns a real child JVM process using ProcessBuilder so the separation is genuine.
 */
public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    // -------------------------------------------------------------------------
    // 1. Standard I/O Pipes
    //    Parent → child via stdin pipe; child replies via stdout pipe.
    // -------------------------------------------------------------------------
    static void demoStdioPipe() throws Exception {
        logger.info("\n===== 1. STDIO PIPE =====");

        // The child program: read one line from stdin, echo it back upper-cased.
        String childCode = """
                import java.util.Scanner;
                public class PipeChild {
                    public static void main(String[] a) throws Exception {
                        Scanner sc = new Scanner(System.in);
                        String line = sc.nextLine();
                        System.out.println("CHILD RECEIVED: " + line.toUpperCase());
                    }
                }
                """;

        Path src = Files.createTempFile("PipeChild", ".java");
        Files.writeString(src, childCode);

        // Compile
        new ProcessBuilder("javac", src.toString()).inheritIO().start().waitFor();

        // Run child, redirecting its stdout back to us
        Process child = new ProcessBuilder(
                "java", "-cp", src.getParent().toString(), "PipeChild")
                .redirectErrorStream(true)
                .start();

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(child.getOutputStream()))) {
            pw.println("hello from parent");
        }

        String reply = new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();
        logger.info("Parent got: " + reply);
        child.waitFor(5, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // 2. File-based IPC
    //    Parent writes a message to a shared file; child reads it and appends a reply.
    // -------------------------------------------------------------------------
    static void demoFileIPC() throws Exception {
        logger.info("\n===== 2. FILE-BASED IPC =====");

        Path sharedFile = Files.createTempFile("ipc_shared_", ".txt");
        Files.writeString(sharedFile, "MESSAGE_FROM_PARENT");

        String childCode = """
                import java.nio.file.*;
                public class FileChild {
                    public static void main(String[] a) throws Exception {
                        Path f = Path.of(a[0]);
                        String content = Files.readString(f);
                        System.out.println("Child read: " + content);
                        Files.writeString(f, content + "\\nMESSAGE_FROM_CHILD",
                                StandardOpenOption.TRUNCATE_EXISTING);
                    }
                }
                """;

        Path src = Files.createTempFile("FileChild", ".java");
        Files.writeString(src, childCode);
        new ProcessBuilder("javac", src.toString()).inheritIO().start().waitFor();

        Process child = new ProcessBuilder(
                "java", "-cp", src.getParent().toString(), "FileChild", sharedFile.toString())
                .inheritIO()
                .start();
        child.waitFor(5, TimeUnit.SECONDS);

        String result = Files.readString(sharedFile);
        logger.info("Shared file now contains:\n" + result);
    }

    // -------------------------------------------------------------------------
    // 3. Socket (TCP)
    //    Child acts as a server; parent connects as client and exchanges messages.
    // -------------------------------------------------------------------------
    static void demoSocket() throws Exception {
        logger.info("\n===== 3. TCP SOCKET =====");

        int port = 19876;

        String serverCode = """
                import java.net.*;
                import java.io.*;
                public class SocketServer {
                    public static void main(String[] a) throws Exception {
                        try (ServerSocket ss = new ServerSocket(Integer.parseInt(a[0]));
                             Socket s  = ss.accept();
                             BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()));
                             PrintWriter   out = new PrintWriter(s.getOutputStream(), true)) {
                            String msg = in.readLine();
                            System.out.println("Server received: " + msg);
                            out.println("ACK: " + msg.toUpperCase());
                        }
                    }
                }
                """;

        Path src = Files.createTempFile("SocketServer", ".java");
        Files.writeString(src, serverCode);
        new ProcessBuilder("javac", src.toString()).inheritIO().start().waitFor();

        // Start server child
        Process server = new ProcessBuilder(
                "java", "-cp", src.getParent().toString(), "SocketServer", String.valueOf(port))
                .inheritIO()
                .start();

        Thread.sleep(500); // give server time to bind

        // Parent acts as client
        try (Socket socket = new Socket("localhost", port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("hello from parent socket");
            String reply = in.readLine();
            logger.info("Client received: " + reply);
        }

        server.waitFor(5, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // 4. Named Pipe / FIFO  (cross-process, Unix only)
    //    A file-system object that acts like a pipe. Writer and reader are
    //    separate processes; the kernel buffers data between them.
    //    Unlike a regular file, reads block until data is written.
    // -------------------------------------------------------------------------
    static void demoNamedPipe() throws Exception {
        logger.info("\n===== 4. NAMED PIPE (FIFO) =====");

        Path fifo = Path.of(System.getProperty("java.io.tmpdir"), "ipc_fifo_demo");
        Files.deleteIfExists(fifo);
        new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor(3, TimeUnit.SECONDS);

        // Reader process: blocks on open() until a writer connects, then reads one line.
        String readerCode = """
                import java.io.*;
                public class FifoReader {
                    public static void main(String[] a) throws Exception {
                        try (BufferedReader br = new BufferedReader(new FileReader(a[0]))) {
                            System.out.println("Reader got: " + br.readLine());
                        }
                    }
                }
                """;
        Path src = Files.createTempFile("FifoReader", ".java");
        Files.writeString(src, readerCode);
        new ProcessBuilder("javac", src.toString()).inheritIO().start().waitFor();

        Process reader = new ProcessBuilder(
                "java", "-cp", src.getParent().toString(), "FifoReader", fifo.toString())
                .inheritIO()
                .start();

        // Parent is the writer — opens the FIFO and sends a message.
        try (PrintWriter pw = new PrintWriter(new FileWriter(fifo.toFile()))) {
            pw.println("hello through named pipe");
        }

        reader.waitFor(5, TimeUnit.SECONDS);
        Files.deleteIfExists(fifo);
    }

    // -------------------------------------------------------------------------
    // 5. Java NIO Pipe  (intra-JVM, inter-thread channel)
    //    java.nio.channels.Pipe provides a unidirectional channel pair within
    //    the same JVM. One thread writes to the Sink; another reads from the Source.
    //    This is the Java-native "channel" abstraction (analogous to Go channels).
    // -------------------------------------------------------------------------
    static void demoNioPipe() throws Exception {
        logger.info("\n===== 5. JAVA NIO PIPE (intra-JVM channel) =====");

        Pipe pipe = Pipe.open();

        // Writer thread → Sink channel
        Thread writer = Thread.ofVirtual().start(() -> {
            try (Pipe.SinkChannel sink = pipe.sink()) {
                ByteBuffer buf = ByteBuffer.wrap("hello through NIO pipe".getBytes(StandardCharsets.UTF_8));
                sink.write(buf);
            } catch (IOException e) {
                logger.severe(e.getMessage());
            }
        });

        // Reader thread → Source channel
        Thread reader = Thread.ofVirtual().start(() -> {
            try (Pipe.SourceChannel source = pipe.source()) {
                ByteBuffer buf = ByteBuffer.allocate(256);
                source.read(buf);
                buf.flip();
                logger.info("Reader got: " + StandardCharsets.UTF_8.decode(buf));
            } catch (IOException e) {
                logger.severe(e.getMessage());
            }
        });

        writer.join();
        reader.join();
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        demoStdioPipe();
        demoFileIPC();
        demoSocket();
        demoNamedPipe();
        demoNioPipe();
    }
}