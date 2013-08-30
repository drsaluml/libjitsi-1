/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.notify;

import java.io.*;

import javax.media.*;
import javax.media.format.*;

import org.jitsi.impl.neomedia.MediaUtils;
import org.jitsi.impl.neomedia.codec.audio.speex.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.service.audionotifier.*;
import org.jitsi.util.*;

/**
 * Implementation of SCAudioClip using PortAudio.
 *
 * @author Damyian Minkov
 * @author Lyubomir Marinov
 */
public class AudioSystemClipImpl
    extends AbstractSCAudioClip
{
    /**
     * The default length of {@link #bufferData}.
     */
    private static final int DEFAULT_BUFFER_DATA_LENGTH = 8 * 1024;

    /**
     * The <tt>Logger</tt> used by the <tt>AudioSystemClipImpl</tt> class and
     * its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(AudioSystemClipImpl.class);

    /**
     * The minimum duration in milliseconds to be assumed for the audio streams
     * played by <tt>AudioSystemClipImpl</tt> in order to ensure that they are
     * played back long enough to be heard.
     */
    private static final long MIN_AUDIO_STREAM_DURATION = 200;

    private final AudioSystem audioSystem;

    private Buffer buffer;

    private byte[] bufferData;

    private final boolean playback;

    private Renderer renderer;

    /**
     * Creates the audio clip and initializes the listener used from the
     * loop timer.
     *
     * @param url the URL pointing to the audio file
     * @param audioNotifier the audio notify service
     * @param playback to use playback or notification device
     * @throws IOException cannot audio clip with supplied URL.
     */
    public AudioSystemClipImpl(
            String url,
            AudioNotifierService audioNotifier,
            AudioSystem audioSystem,
            boolean playback)
        throws IOException
    {
        super(url, audioNotifier);

        this.audioSystem = audioSystem;
        this.playback = playback;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void enterRunInPlayThread()
    {
        logger.debug("Enter run in play thread called");
        buffer = new Buffer();
        bufferData = new byte[DEFAULT_BUFFER_DATA_LENGTH];
        buffer.setData(bufferData);

        renderer = audioSystem.createRenderer(playback);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void exitRunInPlayThread()
    {
        logger.debug("Exit run in play thread called");
        buffer = null;
        bufferData = null;
        renderer = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void exitRunOnceInPlayThread()
    {
        logger.debug("Exit run once in play thread called");
        if (renderer != null)
        {
            try
            {
                renderer.stop();
            }
            finally
            {
                renderer.close();
            }
        }
    }

    @Override
    public boolean isInvalid()
    {
        buffer = new Buffer();
        bufferData = new byte[DEFAULT_BUFFER_DATA_LENGTH];
        buffer.setData(bufferData);

        // Use a temporary renderer, with playback disabled.
        Renderer tempRenderer = audioSystem.createRenderer(false);

        // Unless the renderer explicitly tells us we succeeded, then we failed.
        boolean success = false;

        try
        {
            success = renderAudio(tempRenderer, false);
        }
        catch (IOException ioex) {}
        catch (ResourceUnavailableException ruex) {}

        return !success;
    }

    /**
     * {@inheritDoc}
     */
    protected boolean runOnceInPlayThread()
    {
        logger.debug("Run once in play thread called");

        try
        {
            return renderAudio(renderer, true);
        }
        catch (IOException ioex)
        {
            logger.error(ioex);
            return false;
        }
        catch (ResourceUnavailableException ruex)
        {
            return false;
        }
    }

    /**
     * Renders audio from the file at the given URI.
     * @param renderer The renderer to use for rendering audio.
     * @param notifyListeners Whether to notify listeners when rendering starts
     * and ends.
     * @return <tt>true</tt> iff the rendering was successful.
     * @throws IOException If the file is not accessible, or is invalid.
     * @throws ResourceUnavailableException If the resampler or renderer is not
     * available.
     */
    public boolean renderAudio(Renderer renderer, boolean notifyListeners)
        throws IOException, ResourceUnavailableException
    {
        InputStream audioStream = null;

        try
        {
            audioStream = audioSystem.getAudioInputStream(uri);
        }
        catch (IOException ioex)
        {
            throw new IOException("Failed to get audio stream " + uri, ioex);
        }
        if (audioStream == null)
            return false;

        Codec resampler = null;
        boolean success = true;
        AudioFormat audioStreamFormat = null;
        int audioStreamLength = 0;
        long rendererProcessStartTime = 0;

        try
        {
            Format rendererFormat
                = audioStreamFormat
                    = audioSystem.getFormat(audioStream);

            if (rendererFormat == null || renderer == null)
                return false;

            if (((AudioFormat)rendererFormat).getSampleRate() >
                MediaUtils.MAX_AUDIO_SAMPLE_RATE)
                return false;

            Format resamplerFormat = null;

            if (renderer.setInputFormat(rendererFormat) == null)
            {
                /*
                 * Try to negotiate a resampling of the audioStream to one of
                 * the formats supported by the renderer.
                 */
                resampler = new SpeexResampler();
                resamplerFormat = rendererFormat;
                resampler.setInputFormat(resamplerFormat);

                Format[] supportedResamplerFormats
                    = resampler.getSupportedOutputFormats(resamplerFormat);

                for (Format supportedRendererFormat
                        : renderer.getSupportedInputFormats())
                {
                    for (Format supportedResamplerFormat
                            : supportedResamplerFormats)
                    {
                        if (supportedRendererFormat.matches(
                                supportedResamplerFormat))
                        {
                            rendererFormat = supportedRendererFormat;
                            resampler.setOutputFormat(rendererFormat);
                            renderer.setInputFormat(rendererFormat);
                            break;
                        }
                    }
                }
            }

            if (buffer == null)
                return false;

            Buffer rendererBuffer = buffer;
            Buffer resamplerBuffer;

            rendererBuffer.setFormat(rendererFormat);
            if (resampler == null)
                resamplerBuffer = null;
            else
            {
                resamplerBuffer = new Buffer();

                int bufferDataLength = DEFAULT_BUFFER_DATA_LENGTH;

                if (resamplerFormat instanceof AudioFormat)
                {
                    AudioFormat af = (AudioFormat) resamplerFormat;
                    int frameSize
                        = af.getSampleSizeInBits() / 8 * af.getChannels();

                    bufferDataLength = bufferDataLength / frameSize * frameSize;
                }
                bufferData = new byte[bufferDataLength];
                resamplerBuffer.setData(bufferData);
                resamplerBuffer.setFormat(resamplerFormat);

                resampler.open();
            }

            try
            {
                renderer.open();
                renderer.start();

                if (notifyListeners)
                {
                    fireAudioStartedEvent();
                }

                int bufferLength;

                while (isStarted()
                        && ((bufferLength = audioStream.read(bufferData))
                                != -1))
                {
                    audioStreamLength += bufferLength;

                    if (resampler == null)
                    {
                        rendererBuffer.setLength(bufferLength);
                        rendererBuffer.setOffset(0);
                    }
                    else
                    {
                        resamplerBuffer.setLength(bufferLength);
                        resamplerBuffer.setOffset(0);
                        rendererBuffer.setLength(0);
                        rendererBuffer.setOffset(0);
                        resampler.process(resamplerBuffer, rendererBuffer);
                    }

                    int rendererProcess;

                    if (rendererProcessStartTime == 0)
                        rendererProcessStartTime = System.currentTimeMillis();
                    do
                    {
                        rendererProcess = renderer.process(rendererBuffer);
                        if (rendererProcess == Renderer.BUFFER_PROCESSED_FAILED)
                        {
                            String error = "Failed to render audio stream " +
                                            uri;
                            throw new IOException(error);
                        }
                    }
                    while ((rendererProcess
                                & Renderer.INPUT_BUFFER_NOT_CONSUMED)
                            == Renderer.INPUT_BUFFER_NOT_CONSUMED);
                }

                if (notifyListeners)
                {
                    fireAudioEndedEvent();
                }
            }
            catch (IOException ioex)
            {
                throw new IOException("Failed to read from audio stream " + uri,
                                      ioex);
            }
            catch (ResourceUnavailableException ruex)
            {
                String error = "Failed to open "+renderer.getClass().getName();
                throw new ResourceUnavailableException(error, ruex);
            }
        }
        catch (ResourceUnavailableException ruex)
        {
            if (resampler != null)
            {
                String error = "Failed to open "+resampler.getClass().getName();
                throw new ResourceUnavailableException(error, ruex);
            }
        }
        finally
        {
            try
            {
                audioStream.close();
            }
            catch (IOException ioex)
            {
                /*
                 * The audio stream failed to close but it doesn't mean the URL
                 * will fail to open again so ignore the exception.
                 */
            }

            if (resampler != null)
                resampler.close();

            /*
             * XXX We do not know whether the Renderer implementation of the
             * stop method will wait for the playback to complete.
             */
            if (success
                    && (audioStreamFormat != null)
                    && (audioStreamLength > 0)
                    && (rendererProcessStartTime > 0)
                    && isStarted())
            {
                long audioStreamDuration
                    = (audioStreamFormat.computeDuration(audioStreamLength)
                            + 999999)
                        / 1000000;

                if (audioStreamDuration > 0)
                {
                    /*
                     * XXX The estimation is not accurate because we do not
                     * know, for example, how much the Renderer may be buffering
                     * before it starts the playback.
                     */
                    audioStreamDuration += MIN_AUDIO_STREAM_DURATION;

                    boolean interrupted = false;

                    synchronized (sync)
                    {
                        while (isStarted())
                        {
                            long timeout
                                = System.currentTimeMillis()
                                    - rendererProcessStartTime;

                            if ((timeout >= audioStreamDuration)
                                    || (timeout <= 0))
                            {
                                break;
                            }
                            else
                            {
                                try
                                {
                                    sync.wait(timeout);
                                }
                                catch (InterruptedException ie)
                                {
                                    interrupted = true;
                                }
                            }
                        }
                    }
                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
            }
        }

        return success;
    }
}
