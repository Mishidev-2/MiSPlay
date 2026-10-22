package com.misplay.service;

import com.misplay.model.TrackMetadata;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Blocking, read-only metadata/header extraction; LibraryService owns scheduling. */
public class MetadataService {
    public TrackMetadata read(File file) throws IOException {
        System.out.println("Reading tags: " + file.getName());
        try {
            var audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            List<String> artists = names(tag, FieldKey.ARTISTS, FieldKey.ARTIST);
            return new TrackMetadata(
                    first(tag, FieldKey.TITLE, file.getName()),
                    artists.isEmpty() ? List.of("Unknown artist") : artists,
                    first(tag, FieldKey.ALBUM, "Unknown album"),
                    names(tag, FieldKey.ALBUM_ARTISTS, FieldKey.ALBUM_ARTIST),
                    number(first(tag, FieldKey.TRACK, "")),
                    number(first(tag, FieldKey.DISC_NO, "")),
                    duration(audioFile.getAudioHeader())
            );
        } catch (CannotReadException | TagException
                 | ReadOnlyFileException | InvalidAudioFrameException error) {
            throw new IOException("Cannot read metadata from " + file.getName(), error);
        }
    }

    private static String first(Tag tag, FieldKey key, String fallback) {
        List<String> values = values(tag, key);
        return values.isEmpty() ? fallback : values.get(0);
    }

    private static List<String> names(Tag tag, FieldKey structured, FieldKey standard) {
        List<String> values = values(tag, structured);
        return values.isEmpty() ? values(tag, standard) : values;
    }

    private static List<String> values(Tag tag, FieldKey key) {
        if (tag == null) {
            return List.of();
        }
        List<String> raw;
        try {
            raw = tag.getAll(key);
        } catch (KeyNotFoundException unsupported) {
            // Some tag versions have no mapping for an optional generic key.
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            // NUL separates actual ID3 values. Punctuation may belong to a name.
            for (String part : value.split("\u0000", -1)) {
                String text = part.strip();
                if (!text.isEmpty() && !result.contains(text)) {
                    result.add(text);
                }
            }
        }
        return List.copyOf(result);
    }

    private static Integer number(String value) {
        String text = value.split("/", -1)[0].strip();
        if (text.isEmpty() || !text.matches("[0-9]+")) {
            return null;
        }
        try {
            int number = Integer.parseInt(text);
            return number > 0 ? number : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Long duration(AudioHeader header) {
        if (header == null) {
            return null;
        }
        double seconds = header.getPreciseTrackLength();
        if (!Double.isFinite(seconds) || seconds <= 0) {
            seconds = header.getTrackLength();
        }
        if (!Double.isFinite(seconds) || seconds <= 0
                || seconds >= Long.MAX_VALUE / 1_000.0) {
            return null;
        }
        long millis = Math.round(seconds * 1_000.0);
        return millis > 0 ? millis : null;
    }
}
