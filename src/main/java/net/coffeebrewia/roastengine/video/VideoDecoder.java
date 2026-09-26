package net.coffeebrewia.roastengine.video;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.Transform;
import org.jcodec.scale.ColorUtil;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads an .mp4 a frame at a time, handing back plain RGBA pixels.
 *
 * <p>Decoding is pure Java (JCodec), so a mod can ship a video and it plays on every platform the
 * engine runs on with nothing installed. The trade is speed: H.264 in Java is not free, which is
 * why {@link VideoPlayer} runs this on its own thread and drops frames when it falls behind.
 *
 * <p>This class knows nothing about OpenGL or timing - it is the part that can be tested without
 * a window.
 *
 * <p><b>Video only.</b> An mp4's soundtrack is usually AAC, which JCodec cannot decode, so the
 * sound for a video is shipped beside it as an .ogg (see {@link VideoPlayer}).
 */
public final class VideoDecoder implements AutoCloseable {

    /** One decoded frame: RGBA pixels, and when in the video it belongs. */
    public record Frame(byte[] rgba, int width, int height, double seconds) {
    }

    private final SeekableByteChannel channel;
    private final FrameGrab grab;
    private final int width;
    private final int height;

    /** Reused between frames so a long video does not churn through buffers. */
    private Picture rgbPicture;
    private double lastTimestamp;

    private VideoDecoder(SeekableByteChannel channel, FrameGrab grab, int width, int height) {
        this.channel = channel;
        this.grab = grab;
        this.width = width;
        this.height = height;
    }

    /**
     * Opens a video file.
     *
     * @throws IOException if the file is missing, is not an mp4, or holds a codec JCodec cannot
     *                     read (HEVC, say) - the caller is expected to carry on without it
     */
    public static VideoDecoder open(Path file) throws IOException {
        SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile());
        try {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            var size = grab.getMediaInfo().getDim();
            return new VideoDecoder(channel, grab, size.getWidth(), size.getHeight());
        } catch (IOException e) {
            NIOUtils.closeQuietly(channel);
            throw e;
        } catch (Exception e) {
            // JCodecException, and anything the decoder throws on a file it cannot make sense of.
            NIOUtils.closeQuietly(channel);
            throw new IOException("Not a video this engine can play: " + file.getFileName(), e);
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The next frame, or null at the end of the video.
     *
     * @throws IOException if the file stops making sense part way through
     */
    public Frame next() throws IOException {
        PictureWithMetadata decoded;
        try {
            decoded = grab.getNativeFrameWithMetadata();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("The video stopped part way through", e);
        }
        if (decoded == null) {
            return null;
        }
        // H.264 codes whole 16-pixel blocks, so a 1080-tall video decodes 1088 tall with the
        // extra rows marked as crop. Dropping them here keeps the picture the shape it was shot.
        Picture picture = decoded.getPicture().cropped();
        // Some videos carry a timestamp of 0 on every frame; fall back to counting durations.
        double seconds = decoded.getTimestamp();
        if (seconds <= 0 && lastTimestamp > 0) {
            seconds = lastTimestamp + Math.max(0.001, decoded.getDuration());
        }
        lastTimestamp = seconds;
        return new Frame(toRgba(picture), picture.getWidth(), picture.getHeight(), seconds);
    }

    /**
     * Turns a decoded picture into RGBA bytes, top row first.
     *
     * <p>Frames arrive as YUV; JCodec's own transform does the colour conversion, and its RGB
     * bytes are stored with 128 subtracted, so that comes back on here.
     */
    private byte[] toRgba(Picture picture) {
        Picture source = picture;
        if (source.getColor() != ColorSpace.RGB) {
            if (rgbPicture == null
                    || rgbPicture.getWidth() != source.getWidth()
                    || rgbPicture.getHeight() != source.getHeight()) {
                rgbPicture = Picture.create(source.getWidth(), source.getHeight(), ColorSpace.RGB);
            }
            Transform transform = ColorUtil.getTransform(source.getColor(), ColorSpace.RGB);
            transform.transform(source, rgbPicture);
            source = rgbPicture;
        }
        byte[] rgb = source.getPlaneData(0);
        int pixels = source.getWidth() * source.getHeight();
        byte[] rgba = new byte[pixels * 4];
        for (int i = 0, from = 0, to = 0; i < pixels; i++) {
            rgba[to++] = (byte) (rgb[from++] + 128);
            rgba[to++] = (byte) (rgb[from++] + 128);
            rgba[to++] = (byte) (rgb[from++] + 128);
            rgba[to++] = (byte) 0xFF;
        }
        return rgba;
    }

    @Override
    public void close() {
        NIOUtils.closeQuietly(channel);
    }
}
