package com.funchole.backend.sandbox.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Moves a workspace across the sandbox boundary as {@code .tar.gz}.
 *
 * <p>The archive coming back from a sandbox was written by untrusted code, so {@link #extract} is
 * deliberately strict: it never writes outside the destination (no absolute paths, {@code ..}, or
 * writing through a symlink), only creates regular files, directories and relative symlinks that
 * stay inside the destination, drops setuid/setgid bits, refuses device nodes and hard links, and
 * enforces size and entry-count limits so an archive bomb cannot fill the disk.
 */
public final class SafeTar {

    public record Limits(long maxTotalBytes, long maxEntries) {
        public static final Limits DEFAULT = new Limits(1L << 30, 200_000);
    }

    public static final class UnsafeArchiveException extends IOException {
        public UnsafeArchiveException(String message) {
            super(message);
        }
    }

    private SafeTar() {
    }

    /** Packs {@code directory}'s contents; names listed in {@code excludeTopLevel} are left out. */
    public static void pack(Path directory, OutputStream out, List<String> excludeTopLevel) throws IOException {
        try (var gzip = new GZIPOutputStream(out);
             var tar = new TarArchiveOutputStream(gzip);
             Stream<Path> walk = Files.walk(directory)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            List<Path> paths = walk.filter(path -> !path.equals(directory))
                    .filter(path -> !excludeTopLevel.contains(directory.relativize(path).getName(0).toString()))
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .toList();
            for (Path path : paths) {
                String name = directory.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
                    entry.setLinkName(Files.readSymbolicLink(path).toString());
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                } else if (Files.isDirectory(path)) {
                    TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), name + "/");
                    entry.setMode(0755);
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                } else if (Files.isRegularFile(path)) {
                    TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), name);
                    entry.setMode(Files.isExecutable(path) ? 0755 : 0644);
                    tar.putArchiveEntry(entry);
                    Files.copy(path, tar);
                    tar.closeArchiveEntry();
                }
            }
            tar.finish();
        }
    }

    public static void pack(Path directory, OutputStream out) throws IOException {
        pack(directory, out, List.of());
    }

    /** Extracts into {@code destination} (created if missing). Throws {@link UnsafeArchiveException} on anything suspicious. */
    public static void extract(InputStream in, Path destination, Limits limits) throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toRealPath();
        long total = 0;
        long entries = 0;
        try (var gzip = new GZIPInputStream(in); var tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) {
                    throw new UnsafeArchiveException("archive has more than " + limits.maxEntries() + " entries");
                }
                Path target = resolveInside(root, entry.getName());
                if (target.equals(root)) {
                    // The "./" entry that `tar -C dir .` writes first: the destination itself, nothing to create.
                    if (entry.isDirectory()) {
                        continue;
                    }
                    throw new UnsafeArchiveException("entry replaces the workspace itself: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    ensureRealParentInside(root, target);
                    Files.createDirectories(target);
                } else if (entry.isSymbolicLink()) {
                    ensureRealParentInside(root, target);
                    Files.createDirectories(target.getParent());
                    checkLinkStaysInside(root, target, entry.getLinkName());
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Path.of(entry.getLinkName()));
                } else if (isRegularFile(entry)) {
                    total += entry.getSize();
                    if (total > limits.maxTotalBytes()) {
                        throw new UnsafeArchiveException("archive expands past " + limits.maxTotalBytes() + " bytes");
                    }
                    ensureRealParentInside(root, target);
                    Files.createDirectories(target.getParent());
                    if (Files.isSymbolicLink(target)) {
                        throw new UnsafeArchiveException("refusing to write through a symlink: " + entry.getName());
                    }
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                    // Keep "executable" (node_modules/.bin tools), drop everything else (setuid, setgid, sticky).
                    target.toFile().setExecutable((entry.getMode() & 0111) != 0, false);
                } else {
                    throw new UnsafeArchiveException("unsupported entry type (device, hard link or fifo): " + entry.getName());
                }
            }
        }
        verifyNoLinkEscapes(root);
    }

    /** commons-compress reports devices and hard links as "files" too, so the type flag is checked directly. */
    private static boolean isRegularFile(TarArchiveEntry entry) {
        byte flag = entry.getLinkFlag();
        return !entry.isDirectory() && (flag == TarArchiveEntry.LF_NORMAL || flag == TarArchiveEntry.LF_OLDNORM);
    }

    /**
     * A link's target is resolved against the REAL directory it lives in (an earlier symlink may make
     * the lexical path misleading) and, if it already exists, must really resolve inside the workspace.
     */
    private static void checkLinkStaysInside(Path root, Path link, String linkName) throws IOException {
        Path linkTarget = Path.of(linkName);
        if (linkTarget.isAbsolute()) {
            throw new UnsafeArchiveException("symlink points outside the workspace: " + root.relativize(link));
        }
        Path candidate = link.getParent().toRealPath().resolve(linkTarget).normalize();
        boolean inside = candidate.startsWith(root) && (!Files.exists(candidate) || candidate.toRealPath().startsWith(root));
        if (!inside) {
            throw new UnsafeArchiveException("symlink points outside the workspace: " + root.relativize(link));
        }
    }

    /** Final pass over everything extracted, in case later entries changed what an earlier link resolves to. */
    private static void verifyNoLinkEscapes(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(path)) {
                    checkLinkStaysInside(root, path, Files.readSymbolicLink(path).toString());
                }
            }
        }
    }

    public static void extract(InputStream in, Path destination) throws IOException {
        extract(in, destination, Limits.DEFAULT);
    }

    private static Path resolveInside(Path root, String name) throws IOException {
        if (name.indexOf('\0') >= 0 || name.startsWith("/") || name.startsWith("\\")) {
            throw new UnsafeArchiveException("illegal entry name: " + name);
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new UnsafeArchiveException("entry escapes the workspace: " + name);
        }
        return target;
    }

    /** Guards against an earlier symlink redirecting this entry's parent outside the workspace. */
    private static void ensureRealParentInside(Path root, Path target) throws IOException {
        Path parent = target.getParent();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            parent = parent.getParent();
        }
        if (parent != null && !parent.toRealPath().startsWith(root)) {
            throw new UnsafeArchiveException("entry would be written outside the workspace via a symlink: " + root.relativize(target));
        }
    }
}
