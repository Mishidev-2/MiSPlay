package com.misplay.service;

import com.misplay.model.LyricLine;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v22Tag;
import org.jaudiotagger.tag.id3.ID3v23Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Blocking, read-only ID3 lyric reader. The caller supplies a background worker. */
public final class EmbeddedLyricsReader {
    private static final int MAX_TAG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 262_144;
    private static final int MAX_VARIANTS = 32;

    /** Exactly one of timedLines/plainLines may be nonempty. */
    public record Result(List<LyricLine> timedLines, List<String> plainLines, String note) {
        public Result {
            timedLines = List.copyOf(timedLines);
            plainLines = List.copyOf(plainLines);
            if (!timedLines.isEmpty() && !plainLines.isEmpty()) {
                throw new IllegalArgumentException("Lyrics cannot be both timed and plain.");
            }
        }
    }

    public Result read(Path audioPath) throws IOException {
        AbstractID3v2Tag tag = readTag(audioPath);
        if (tag == null) {
            return new Result(List.of(), List.of(), "");
        }

        List<String> firstPlain = List.of();
        String note = "";
        int variants = 0;
        // USLT may contain either ordinary words or an entire LRC document.
        for (String value : tag.getAll(FieldKey.LYRICS)) {
            if (++variants > MAX_VARIANTS) {
                break;
            }
            try {
                String text = cleanText(value);
                if (text.isBlank()) {
                    continue;
                }
                List<LyricLine> timed = LrcParser.parse(text);
                if (!timed.isEmpty()) {
                    return new Result(timed, List.of(), "");
                }
                if (firstPlain.isEmpty()) {
                    firstPlain = plainLines(text);
                }
            } catch (IllegalArgumentException invalid) {
                note = invalid.getMessage();
            }
        }

        // A real SYLT frame stores encoded strings followed by binary timestamps.
        variants = 0;
        var fields = tag.getFields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!(field instanceof AbstractID3v2Frame frame)
                    || !(frame.getBody() instanceof FrameBodySYLT body)) {
                continue;
            }
            if (++variants > MAX_VARIANTS) {
                break;
            }
            if (body.getContentType() != 1 && body.getContentType() != 2) {
                continue;
            }
            if (body.getTimeStampFormat() != 2) {
                note = "Embedded MPEG-frame lyric timing is not supported yet.";
                continue;
            }
            try {
                List<LyricLine> timed = readSylt(body);
                if (!timed.isEmpty()) {
                    return new Result(timed, List.of(), "");
                }
            } catch (IOException | IllegalArgumentException invalid) {
                note = "Some embedded timed lyrics could not be read. " + invalid.getMessage();
            }
        }
        return new Result(List.of(), firstPlain, note);
    }

    private static AbstractID3v2Tag readTag(Path path) throws IOException {
        // Read only the bounded leading tag; no audio decode or tag/file write occurs.
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(10);
            if (header.length < 3 || header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
                return null;
            }
            if (header.length != 10) {
                throw new IOException("The embedded tag header is incomplete.");
            }
            int size = 0;
            for (int index = 6; index < 10; index++) {
                if ((header[index] & 0x80) != 0) {
                    throw new IOException("The embedded tag size is invalid.");
                }
                size = (size << 7) | header[index];
            }
            if (size > MAX_TAG_BYTES - header.length) {
                throw new IOException("The embedded tag exceeds 8 MiB.");
            }
            byte[] body = input.readNBytes(size);
            if (body.length != size) {
                throw new IOException("The embedded tag is incomplete.");
            }
            ByteBuffer buffer = ByteBuffer.allocate(header.length + size);
            buffer.put(header).put(body).flip();
            try {
                return switch (header[3]) {
                    case 2 -> new ID3v22Tag(buffer, path.getFileName().toString());
                    case 3 -> new ID3v23Tag(buffer, path.getFileName().toString());
                    case 4 -> new ID3v24Tag(buffer, path.getFileName().toString());
                    default -> throw new IOException("This embedded tag version is not supported.");
                };
            } catch (TagException | RuntimeException invalid) {
                throw new IOException("The embedded tag could not be read.", invalid);
            }
        }
    }

    private static String cleanText(String value) {
        if (value.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("Embedded lyric text exceeds 262,144 characters.");
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text.strip();
    }

    private static List<String> plainLines(String text) {
        List<String> result = text.lines().toList();
        if (result.size() > LrcParser.MAX_CUES) {
            throw new IllegalArgumentException("Embedded lyrics exceed 10,000 lines.");
        }
        for (String line : result) {
            if (line.length() > LrcParser.MAX_LINE_LENGTH) {
                throw new IllegalArgumentException("An embedded lyric line exceeds 8,192 characters.");
            }
        }
        return result;
    }

    private static List<LyricLine> readSylt(FrameBodySYLT body) throws IOException {
        // Jaudiotagger 3.0.1 splits SYLT's description at a single Latin-1 NUL,
        // including for UTF-16. Rejoin those lossless bytes and skip the actual
        // encoding-sized terminator so UTF-16 lyrics do not start one byte late.
        byte[] prefix = body.getDescription().getBytes(StandardCharsets.ISO_8859_1);
        byte[] payload = body.getLyrics();
        if (prefix.length + 1L + payload.length > 1024 * 1024) {
            throw new IOException("The embedded timed payload exceeds 1 MiB.");
        }
        byte[] data = ByteBuffer.allocate(prefix.length + 1 + payload.length)
                .put(prefix).put((byte) 0).put(payload).array();
        int encoding = body.getTextEncoding();
        int unit = encoding == 1 || encoding == 2 ? 2 : 1;
        Charset charset = switch (encoding) {
            case 0 -> StandardCharsets.ISO_8859_1;
            case 1, 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> throw new IOException("The embedded lyric encoding is unsupported.");
        };
        List<LyricLine> entries = new ArrayList<>();
        int position = 0;
        while (position + unit <= data.length
                && !(data[position] == 0 && (unit == 1 || data[position + 1] == 0))) {
            position += unit;
        }
        if (position + unit > data.length) {
            throw new IOException("The embedded lyric description is incomplete.");
        }
        if (encoding == 1 && position >= 2
                && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xfe) {
            charset = StandardCharsets.UTF_16LE;
        }
        position += unit;
        int characters = 0;
        while (position < data.length) {
            int end = position;
            while (end + unit <= data.length
                    && !(data[end] == 0 && (unit == 1 || data[end + 1] == 0))) {
                end += unit;
            }
            if (end + unit + 4 > data.length) {
                throw new IOException("An embedded lyric entry is incomplete.");
            }
            if (encoding == 1 && end - position >= 2) {
                if ((data[position] & 0xff) == 0xff && (data[position + 1] & 0xff) == 0xfe) {
                    charset = StandardCharsets.UTF_16LE;
                } else if ((data[position] & 0xff) == 0xfe && (data[position + 1] & 0xff) == 0xff) {
                    charset = StandardCharsets.UTF_16BE;
                }
            }
            String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, position, end - position)).toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            characters += text.length();
            if (characters > MAX_TEXT_CHARS || entries.size() >= LrcParser.MAX_CUES) {
                throw new IOException("Embedded timed lyrics exceed the text or entry limit.");
            }
            position = end + unit;
            long millis = Integer.toUnsignedLong(ByteBuffer.wrap(data, position, 4).getInt());
            position += 4;
            entries.add(new LyricLine(millis, text));
        }

        // Standard syllable entries use newlines to mark lyric lines. If no line
        // breaks are present, retain each entry as a timed row instead of guessing.
        if (entries.stream().anyMatch(line -> line.text().contains("\n"))) {
            List<LyricLine> joined = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            long start = 0;
            for (LyricLine entry : entries) {
                String[] parts = entry.text().split("\n", -1);
                for (int index = 0; index < parts.length; index++) {
                    if (index > 0) {
                        if (!text.isEmpty() || !joined.isEmpty()) {
                            joined.add(new LyricLine(start, text.toString().strip()));
                        }
                        text.setLength(0);
                    }
                    if (text.isEmpty()) {
                        start = entry.timeMillis();
                    }
                    text.append(parts[index]);
                    if (text.length() > LrcParser.MAX_LINE_LENGTH
                            || joined.size() > LrcParser.MAX_CUES) {
                        throw new IOException("Embedded timed lyrics exceed the line limit.");
                    }
                }
            }
            if (!text.isEmpty()) {
                joined.add(new LyricLine(start, text.toString().strip()));
            }
            if (joined.size() > LrcParser.MAX_CUES) {
                throw new IOException("Embedded timed lyrics exceed 10,000 lines.");
            }
            entries = joined;
        }
        var groups = new TreeMap<Long, List<String>>();
        for (LyricLine entry : entries) {
            if (entry.text().length() > LrcParser.MAX_LINE_LENGTH) {
                throw new IOException("An embedded lyric line exceeds 8,192 characters.");
            }
            groups.computeIfAbsent(entry.timeMillis(), ignored -> new ArrayList<>()).add(entry.text().strip());
        }
        List<LyricLine> result = new ArrayList<>();
        groups.forEach((time, texts) -> result.add(new LyricLine(time, String.join("\n", texts))));
        return List.copyOf(result);
    }
}
