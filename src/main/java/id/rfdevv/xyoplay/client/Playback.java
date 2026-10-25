package id.rfdevv.xyoplay.client;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Track navigation shared by the GUI buttons and the auto-advance logic. */
final class Playback {
    private static String activePlaylist;

    private Playback() {}

    /** Called when a track finished by itself. */
    static void onTrackEnded() {
        List<Path> tracks = currentTracks();
        Path previous = LocalAudioPlayer.currentPath();
        if (tracks.isEmpty() || previous == null) return;
        int idx = tracks.indexOf(previous);
        if (ClientSettings.repeat) { LocalAudioPlayer.play(previous); return; }
        Path queued = MusicLibraryData.takeNextQueued();
        if (queued != null) { activePlaylist = null; LocalAudioPlayer.play(queued); return; }
        if (ClientSettings.shuffle && tracks.size() > 1) { LocalAudioPlayer.play(tracks.get(randomOther(tracks.size(), idx))); return; }
        if (idx >= 0 && idx < tracks.size() - 1) LocalAudioPlayer.play(tracks.get(idx + 1));
    }

    /** Manual next/previous. Returns the new track or null when the library is empty. */
    static Path step(int direction) {
        List<Path> tracks = currentTracks();
        if (tracks.isEmpty()) return null;
        Path current = LocalAudioPlayer.currentPath();
        int idx = current == null ? -1 : tracks.indexOf(current);
        int next;
        Path queued = direction > 0 ? MusicLibraryData.takeNextQueued() : null;
        if (queued != null) {
            activePlaylist = null;
            LocalAudioPlayer.play(queued);
            return queued;
        }
        if (direction < 0 && current != null && LocalAudioPlayer.isPlayingOrPaused() && LocalAudioPlayer.positionSeconds() > 3) next = Math.max(0, idx);
        else if (direction > 0 && ClientSettings.shuffle && tracks.size() > 1) next = randomOther(tracks.size(), idx);
        else if (idx < 0) next = direction > 0 ? 0 : tracks.size() - 1;
        else next = Math.floorMod(idx + direction, tracks.size());
        Path target = tracks.get(next);
        LocalAudioPlayer.play(target);
        return target;
    }

    static void playPlaylist(String name, Path track) {
        activePlaylist = name;
        LocalAudioPlayer.play(track);
    }

    static void playTrack(Path track) {
        activePlaylist = null;
        LocalAudioPlayer.play(track);
    }

    private static List<Path> currentTracks() {
        if (activePlaylist != null) {
            List<Path> tracks = MusicLibraryData.playlists().stream()
                    .filter(playlist -> playlist.name().equals(activePlaylist))
                    .map(MusicLibraryData.Playlist::tracks)
                    .findFirst().orElse(List.of());
            if (!tracks.isEmpty()) return tracks;
            activePlaylist = null;
        }
        return MusicLibrary.tracks();
    }

    private static int randomOther(int size, int avoid) {
        int next;
        do { next = ThreadLocalRandom.current().nextInt(size); } while (next == avoid);
        return next;
    }
}
