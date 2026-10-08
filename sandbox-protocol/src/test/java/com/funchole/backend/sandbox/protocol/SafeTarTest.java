package com.funchole.backend.sandbox.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeTarTest {

    @TempDir
    Path work;

    /** Builds an archive by hand so the tests can craft hostile entries a real packer never would. */
    private interface Writer {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private static byte[] archive(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            writer.write(tar);
            tar.finish();
        }
        return bytes.toByteArray();
    }

    private static void file(TarArchiveOutputStream tar, String name, String content, int mode) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(data.length);
        entry.setMode(mode);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private static void symlink(TarArchiveOutputStream tar, String name, String target) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
        entry.setLinkName(target);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private void extract(byte[] data) throws IOException {
        SafeTar.extract(new ByteArrayInputStream(data), work.resolve("out"));
    }

    @Test
    void roundTripsFilesDirectoriesExecutableBitAndSafeSymlinks() throws Exception {
        Path source = Files.createDirectories(work.resolve("src"));
        Files.writeString(source.resolve("a.txt"), "hello");
        Files.createDirectories(source.resolve("node_modules/.bin"));
        Files.createDirectories(source.resolve("node_modules/pkg/bin"));
        Path tool = Files.writeString(source.resolve("node_modules/pkg/bin/cli.js"), "#!/usr/bin/env node");
        tool.toFile().setExecutable(true);
        Files.createSymbolicLink(source.resolve("node_modules/.bin/cli"), Path.of("../pkg/bin/cli.js"));
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        SafeTar.pack(source, packed);

        extract(packed.toByteArray());

        Path out = work.resolve("out");
        assertThat(Files.readString(out.resolve("a.txt"))).isEqualTo("hello");
        assertThat(Files.isExecutable(out.resolve("node_modules/pkg/bin/cli.js"))).isTrue();
        assertThat(Files.isSymbolicLink(out.resolve("node_modules/.bin/cli"))).isTrue();
        assertThat(Files.readString(out.resolve("node_modules/.bin/cli"))).isEqualTo("#!/usr/bin/env node");
    }

    @Test
    void excludesTopLevelDirectoriesWhenPacking() throws Exception {
        Path source = Files.createDirectories(work.resolve("src"));
        Files.createDirectories(source.resolve("node_modules/x"));
        Files.writeString(source.resolve("node_modules/x/big.js"), "x");
        Files.createDirectories(source.resolve("dist"));
        Files.writeString(source.resolve("dist/index.html"), "<html>");
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        SafeTar.pack(source, packed, List.of("node_modules"));

        extract(packed.toByteArray());

        assertThat(work.resolve("out/dist/index.html")).exists();
        assertThat(work.resolve("out/node_modules")).doesNotExist();
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() throws Exception {
        assertThatThrownBy(() -> extract(archive(tar -> file(tar, "../escape.txt", "x", 0644)))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
        assertThatThrownBy(() -> extract(archive(tar -> file(tar, "a/../../escape.txt", "x", 0644)))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
        // The writer library strips a leading "/" itself, so an absolute name lands safely INSIDE the workspace.
        extract(archive(tar -> file(tar, "/etc/cron.d/x", "x", 0644)));
        assertThat(work.resolve("out/etc/cron.d/x")).exists();
        assertThat(work.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void rejectsSymlinksThatPointOutsideTheWorkspace() {
        assertThatThrownBy(() -> extract(archive(tar -> symlink(tar, "evil", "/etc")))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
        assertThatThrownBy(() -> extract(archive(tar -> symlink(tar, "evil", "../../..")))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
    }

    @Test
    void neverWritesThroughASymlinkEvenOneThatLooksInternal() throws Exception {
        // "dir" -> "." is inside the workspace, but "dir/../" tricks and later entries must not escape.
        byte[] hostile = archive(tar -> {
            symlink(tar, "loop", ".");
            symlink(tar, "loop/up", "..");
        });
        assertThatThrownBy(() -> extract(hostile)).isInstanceOf(SafeTar.UnsafeArchiveException.class);
    }

    @Test
    void rejectsAFileWrittenOverAnExistingSymlink() throws Exception {
        Files.createDirectories(work.resolve("out"));
        Files.createSymbolicLink(work.resolve("out/link"), Path.of("real"));
        Files.createDirectories(work.resolve("out/real"));
        assertThatThrownBy(() -> extract(archive(tar -> file(tar, "link", "x", 0644)))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
    }

    @Test
    void rejectsDeviceNodesAndHardLinks() {
        assertThatThrownBy(() -> extract(archive(tar -> {
            TarArchiveEntry entry = new TarArchiveEntry("dev", TarArchiveEntry.LF_CHR);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
        assertThatThrownBy(() -> extract(archive(tar -> {
            TarArchiveEntry entry = new TarArchiveEntry("hard", TarArchiveEntry.LF_LINK);
            entry.setLinkName("a.txt");
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
    }

    @Test
    void stripsSetuidAndSetgidButKeepsExecutable() throws Exception {
        extract(archive(tar -> file(tar, "tool", "x", 04755)));
        Path tool = work.resolve("out/tool");
        assertThat(Files.isExecutable(tool)).isTrue();
        int mode = (int) Files.getAttribute(tool, "unix:mode");
        assertThat(mode & 06000).as("setuid/setgid bits").isZero();
    }

    @Test
    void enforcesSizeAndEntryLimits() {
        assertThatThrownBy(() -> SafeTar.extract(new ByteArrayInputStream(archive(tar -> file(tar, "big", "x".repeat(2000), 0644))),
                work.resolve("o1"), new SafeTar.Limits(1000, 100))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
        assertThatThrownBy(() -> SafeTar.extract(new ByteArrayInputStream(archive(tar -> {
            for (int i = 0; i < 5; i++) file(tar, "f" + i, "x", 0644);
        })), work.resolve("o2"), new SafeTar.Limits(1000, 3))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
    }

    @Test
    void acceptsTheDotDirectoryEntryWrittenByTarDashCDot() throws Exception {
        extract(archive(tar -> {
            tar.putArchiveEntry(new TarArchiveEntry("./"));
            tar.closeArchiveEntry();
            file(tar, "./package.json", "{}", 0644);
        }));
        assertThat(work.resolve("out/package.json")).exists();
        assertThatThrownBy(() -> extract(archive(tar -> file(tar, ".", "x", 0644)))).isInstanceOf(SafeTar.UnsafeArchiveException.class);
    }
}
