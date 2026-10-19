import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Merges extra files (classes.dex) into an aapt2-linked APK and 4-byte aligns every STORED entry,
 * which Android requires for resources.arsc on targetSdk 30+. Replaces zip + zipalign, neither of
 * which runs natively in Termux.
 *
 * Usage: java ApkPack.java <in.apk> <out.apk> <file>...
 */
public class ApkPack {

    private static final int ALIGNMENT = 4;
    private static final long FIXED_TIME = 315532800000L + 86400000L; // 1980-01-02, reproducible

    static final class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: ApkPack <in.apk> <out.apk> <file>...");
            System.exit(2);
        }
        try (ZipFile in = new ZipFile(args[0]);
             CountingStream counter = new CountingStream(Files.newOutputStream(Paths.get(args[1])));
             ZipOutputStream out = new ZipOutputStream(counter)) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                try (InputStream is = in.getInputStream(e)) {
                    write(out, counter, e.getName(), is.readAllBytes(), e.getMethod() == ZipEntry.STORED);
                }
            }
            for (int i = 2; i < args.length; i++) {
                Path p = Paths.get(args[i]);
                write(out, counter, p.getFileName().toString(), Files.readAllBytes(p), false);
            }
        }
    }

    private static void write(ZipOutputStream out, CountingStream counter, String name, byte[] data, boolean stored)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
            out.flush();
            // Local header = 30 bytes + name + extra. Pad the extra field so the data starts aligned.
            long dataStart = counter.count + 30 + name.getBytes("UTF-8").length;
            int pad = (int) ((ALIGNMENT - (dataStart + 6) % ALIGNMENT) % ALIGNMENT);
            byte[] extra = new byte[6 + pad];
            // 0xD935 is the extra-field id zipalign uses for alignment padding.
            extra[0] = (byte) 0x35;
            extra[1] = (byte) 0xD9;
            extra[2] = (byte) ((2 + pad) & 0xFF);
            extra[3] = (byte) (((2 + pad) >> 8) & 0xFF);
            extra[4] = (byte) ALIGNMENT;
            extra[5] = 0;
            entry.setExtra(extra);
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }
}
