package com.revesoft.tms;

import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * A MySQL server for tests and local development, without installing MySQL or Docker.
 *
 * <p>Uses, in order:
 * <ol>
 *   <li>an existing server when {@code TMS_TEST_DB_URL} (plus {@code TMS_TEST_DB_USERNAME} /
 *       {@code TMS_TEST_DB_PASSWORD}) is set, e.g. on a CI machine;</li>
 *   <li>otherwise a private {@code mysqld} started from an unpacked MySQL "no-install" archive found
 *       in {@code MYSQL_HOME} or {@code ~/.tms/mysql-*} (download the ZIP / tar.gz for your OS from
 *       dev.mysql.com and unpack it there). It listens on 127.0.0.1 only, with a root user and no
 *       password, and stops when the JVM exits.</li>
 * </ol>
 */
final class LocalMysql {

    private static final String JDBC_OPTIONS =
            "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
                    + "&allowPublicKeyRetrieval=true&useSSL=false&createDatabaseIfNotExist=true";

    private LocalMysql() {
    }

    /**
     * A data source for {@code database}.
     *
     * @param dataDir  where the private server keeps its files
     * @param port     0 = any free port
     * @param fresh    drop and re-create the database first (tests)
     */
    static DataSource dataSource(String database, Path dataDir, int port, boolean fresh) {
        String external = System.getenv("TMS_TEST_DB_URL");
        String baseUrl;
        String user;
        String password;
        if (external != null && !external.isBlank()) {
            baseUrl = external.replaceAll("/[^/?]*(\\?.*)?$", "");
            user = env("TMS_TEST_DB_USERNAME", "root");
            password = env("TMS_TEST_DB_PASSWORD", "");
        } else {
            int p = port == 0 ? freePort() : port;
            start(dataDir, p);
            baseUrl = "jdbc:mysql://127.0.0.1:" + p;
            user = "root";
            password = "";
        }
        if (fresh) {
            execute(baseUrl + "/" + JDBC_OPTIONS, user, password,
                    "DROP DATABASE IF EXISTS `" + database + "`",
                    "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(baseUrl + "/" + database + JDBC_OPTIONS);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(10);
        return ds;
    }

    // ------------------------------------------------------------------ private server

    private static synchronized void start(Path dataDir, int port) {
        Path home = mysqlHome();
        Path mysqld = home.resolve("bin").resolve(isWindows() ? "mysqld.exe" : "mysqld");
        try {
            Path data = dataDir.toAbsolutePath();
            if (!Files.isDirectory(data.resolve("mysql"))) {
                Files.createDirectories(data.getParent());
                deleteRecursively(data);
                run(List.of(mysqld.toString(), "--no-defaults", "--initialize-insecure",
                        "--basedir=" + home, "--datadir=" + data), home);
            }
            File log = data.resolveSibling(data.getFileName() + ".log").toFile();
            Path pidFile = data.resolveSibling(data.getFileName() + ".pid");
            stopStale(pidFile);
            Process server = new ProcessBuilder(mysqld.toString(), "--no-defaults", "--basedir=" + home,
                    "--datadir=" + data, "--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=OFF",
                    "--skip-log-bin", "--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M",
                    "--character-set-server=utf8mb4", "--pid-file=" + pidFile, "--console")
                    .directory(home.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.destroy();
                try {
                    server.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            watchdog(server.pid());
            waitUntilReady(server, "jdbc:mysql://127.0.0.1:" + port + "/" + JDBC_OPTIONS, log);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Stops mysqld when this JVM ends for any reason. Shutdown hooks are not enough: build tools
     * may halt the test JVM without running them.
     */
    private static void watchdog(long mysqldPid) throws IOException {
        long me = ProcessHandle.current().pid();
        List<String> command = isWindows()
                ? List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Wait-Process -Id " + me + " -ErrorAction SilentlyContinue; "
                                + "Stop-Process -Id " + mysqldPid + " -Force -ErrorAction SilentlyContinue")
                : List.of("sh", "-c", "while kill -0 " + me + " 2>/dev/null; do sleep 1; done; kill " + mysqldPid);
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** A server left running by a JVM that was killed (shutdown hooks skipped) still locks the files. */
    private static void stopStale(Path pidFile) throws IOException {
        if (!Files.exists(pidFile)) {
            return;
        }
        String pid = Files.readString(pidFile).trim();
        if (pid.chars().allMatch(Character::isDigit) && !pid.isEmpty()) {
            ProcessHandle.of(Long.parseLong(pid))
                    .filter(h -> h.info().command().map(c -> c.contains("mysqld")).orElse(false))
                    .ifPresent(h -> {
                        h.destroy();
                        h.onExit().orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join();
                    });
        }
        Files.deleteIfExists(pidFile);
    }

    private static Path mysqlHome() {
        String env = System.getenv("MYSQL_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path tms = Path.of(System.getProperty("user.home"), ".tms");
        try (Stream<Path> dirs = Files.isDirectory(tms) ? Files.list(tms) : Stream.empty()) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("mysql-"))
                    .filter(d -> Files.exists(d.resolve("bin").resolve(isWindows() ? "mysqld.exe" : "mysqld")))
                    .sorted()
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new IllegalStateException("""
                            No MySQL found for tests. Either set TMS_TEST_DB_URL to an existing MySQL 8 database, \
                            or unpack the MySQL 8.4 no-install archive into %s (or set MYSQL_HOME).""".formatted(tms)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void waitUntilReady(Process server, String url, File log) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            if (!server.isAlive()) {
                throw new IllegalStateException("mysqld exited; see " + log);
            }
            try (Connection ignored = DriverManager.getConnection(url, "root", "")) {
                return;
            } catch (SQLException e) {
                last = e;
            }
            sleep(500);
        }
        throw new IllegalStateException("mysqld did not start within 90 s; see " + log, last);
    }

    private static void execute(String url, String user, String password, String... sql) {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot prepare the test database at " + url, e);
        }
    }

    private static void run(List<String> command, Path dir) throws IOException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        try {
            if (p.waitFor() != 0) {
                throw new IllegalStateException("Command failed: " + command + "\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }
}
