/*
 * Standalone PlaybackQueue regression checks for a Java 25 project.
 * No media files or third-party libraries are required.
 * IntelliJ: right-click this file -> Run 'CheckPlaybackQueue.main()'.
 * Terminal (JDK 25): javac -d target/queue-check src/main/java/com/misplay/model/RepeatMode.java src/main/java/com/misplay/model/PlaybackQueue.java tools/CheckPlaybackQueue.java
 *                 java -cp target/queue-check CheckPlaybackQueue
 */
import com.misplay.model.PlaybackQueue;
import com.misplay.model.PlaybackQueue.Entry;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CheckPlaybackQueue {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static List<File> files(int size) {
        List<File> result = new ArrayList<>();
        for (int n = 0; n < size; n++) result.add(new File("song-" + n + ".mp3"));
        return result;
    }

    private static void toggleCycles(int size, int songIndex, int cycles) {
        PlaybackQueue queue = new PlaybackQueue();
        List<File> library = files(size);
        queue.start(library, library.get(songIndex));
        List<Entry> base = queue.entries();
        Entry playing = queue.current();
        for (int i = 0; i < cycles; i++) {
            queue.setShuffleEnabled(true);
            check(queue.isShuffleEnabled(), "Shuffle must be on");
            check(queue.current().id() == playing.id(), "Shuffle changed the current occurrence");
            check(queue.currentIndex() == songIndex, "Shuffle moved the current position");
            if (size - songIndex - 1 >= 2) {
                check(!queue.entries().equals(base), "Upcoming songs were not shuffled on cycle " + i);
            } else {
                check(queue.entries().equals(base), "Queue with fewer than two upcoming songs must not change");
            }
            queue.setShuffleEnabled(false);
            check(!queue.isShuffleEnabled(), "Shuffle must be off");
            check(queue.entries().equals(base), "Original queue order was not restored");
            check(queue.current().id() == playing.id(), "Restore lost the current occurrence");
            check(queue.currentIndex() == songIndex, "Restore lost current row index");
        }
    }

    public static void main(String[] args) {
        toggleCycles(12, 0, 40);      // first entry
        toggleCycles(12, 5, 40);      // middle entry
        toggleCycles(12, 9, 40);      // exactly two upcoming entries
        toggleCycles(12, 10, 5);      // only one upcoming entry
        toggleCycles(12, 11, 5);      // final entry

        PlaybackQueue q = new PlaybackQueue();
        List<File> songs = files(6);
        q.start(songs, songs.get(2));
        q.setShuffleEnabled(true);
        Entry playing = q.current();
        q.next();
        q.setShuffleEnabled(false);
        check(q.current().id() != playing.id(), "Navigation must change occurrence");
        check(q.current().equals(q.entries().get(q.currentIndex())), "Current index must track current ID");
        System.out.println("PASS: " + checks + " PlaybackQueue checks");
    }
}
