package com.misplay.service;

import com.misplay.model.LyricLine;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Simple line-timed LRC; no file access, playback or JavaFX state. */
public final class LrcParser {
    public static final int MAX_CUES = 10_000;
    public static final int MAX_LINE_LENGTH = 8_192;

    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\[(\\d{1,6}):([0-5]\\d)(?:\\.(\\d{1,3}))?\\]"
    );
    private static final Pattern OFFSET = Pattern.compile(
            "\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WORD_TIME = Pattern.compile(
            "<\\d{1,6}:[0-5]\\d(?:\\.\\d{1,3})?>"
    );

    private LrcParser() {}

    public static List<LyricLine> parse(String content) {
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        List<LyricLine> cues = new ArrayList<>();
        int offsetMillis = 0;

        // String.lines handles LF, CRLF and CR without treating Unicode lyric text as markup.
        try (var lines = content.lines()) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String raw = iterator.next();
                if (raw.length() > MAX_LINE_LENGTH) {
                    throw new IllegalArgumentException("A lyric line exceeds 8,192 characters.");
                }

                String line = raw.strip();
                var offset = OFFSET.matcher(line);
                if (offset.matches()) {
                    try {
                        // The last valid offset applies to the whole file, wherever it occurs.
                        offsetMillis = Integer.parseInt(offset.group(1));
                    } catch (NumberFormatException ignored) {
                        // An out-of-range tag is malformed, like any other unsupported header.
                    }
                    continue;
                }

                var timestamp = TIMESTAMP.matcher(line);
                List<Long> times = new ArrayList<>();
                int position = 0;
                while (position < line.length()) {
                    timestamp.region(position, line.length());
                    if (!timestamp.lookingAt()) {
                        break;
                    }
                    String fraction = timestamp.group(3);
                    long millis = Long.parseLong(timestamp.group(1)) * 60_000
                            + Long.parseLong(timestamp.group(2)) * 1_000
                            + (fraction == null ? 0
                                    : Integer.parseInt((fraction + "00").substring(0, 3)));
                    times.add(millis);
                    if (cues.size() + times.size() > MAX_CUES) {
                        throw new IllegalArgumentException("Lyrics exceed 10,000 timed entries.");
                    }
                    position = timestamp.end();
                    while (position < line.length()
                            && Character.isWhitespace(line.charAt(position))) {
                        position++;
                    }
                }

                // Untimed text, headers and invalid leading timestamps are ignored.
                if (!times.isEmpty()) {
                    String text = WORD_TIME.matcher(line.substring(position)).replaceAll("").strip();
                    for (long time : times) {
                        cues.add(new LyricLine(time, text));
                    }
                }
            }
        }

        // Positive LRC offsets show lyrics sooner. Clamp pre-zero cues to zero.
        // A group keeps translations at the same time visible together in file order.
        var groups = new TreeMap<Long, List<String>>();
        for (LyricLine cue : cues) {
            long adjusted = Math.max(0, cue.timeMillis() - offsetMillis);
            groups.computeIfAbsent(adjusted, ignored -> new ArrayList<>()).add(cue.text());
        }
        List<LyricLine> result = new ArrayList<>();
        groups.forEach((time, texts) -> result.add(new LyricLine(
                time, String.join("\n", texts.stream().filter(text -> !text.isEmpty()).toList())
        )));
        return List.copyOf(result);
    }
}
