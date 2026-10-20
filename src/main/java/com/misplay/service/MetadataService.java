package com.misplay.service;

import com.misplay.model.TrackMetadata;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;

import java.io.File;
import java.io.IOException;

public class MetadataService {
    public TrackMetadata read(File file) throws IOException {
        System.out.println("Reading tags: " + file.getName());
        try {
            Tag tag = AudioFileIO.read(file).getTag();

            return new TrackMetadata(
                    readField(tag, FieldKey.TITLE, file.getName()),
                    readField(tag, FieldKey.ARTIST, "Unknown artist"),
                    readField(tag, FieldKey.ALBUM, "Unknown album")
            );
        } catch (CannotReadException | TagException
                 | ReadOnlyFileException | InvalidAudioFrameException error) {
            throw new IOException(
                    "Cannot read metadata from " + file.getName(),
                    error
            );
        }
    }

    private String readField(Tag tag, FieldKey key, String fallback) {
        if (tag == null) {
            return fallback;
        }

        String value = tag.getFirst(key);

        return value == null || value.isBlank()
                ? fallback
                : value.strip();
    }
}