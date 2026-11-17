package co.voik.agesandtheart.imaging.client;

/** A player's render state carrying whether the recorder is held up before the face, set as the player is posed behind a viewfinder; outside the Mixin package, which nothing else may load from. */
public interface RecorderRaised {

    boolean agesandtheart_imaging$isRecorderRaised();

    void agesandtheart_imaging$setRecorderRaised(boolean raised);
}
