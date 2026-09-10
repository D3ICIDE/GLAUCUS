package utils;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import java.util.concurrent.ExecutionException;

public class VoiceManager {

    private static final String SPEECH_KEY = System.getenv("AZURE_SPEECH_KEY");
    private static final String SPEECH_REGION = System.getenv("AZURE_SPEECH_REGION");

    private static final String RECOGNITION_LANGUAGE = "hi-IN";
    private static final String SYNTHESIS_VOICE = "hi-IN-SwaraNeural";

    /**
     * Listens on the default microphone and returns recognized text.
     * Returns "" if nothing was recognized or credentials are missing.
     */
    public static String voice() {
        if (SPEECH_KEY == null || SPEECH_REGION == null) {
            System.err.println("Error: Missing AZURE_SPEECH_KEY or AZURE_SPEECH_REGION environment variables.");
            return "";
        }

        System.out.println("Initializing Azure Speech Service (recognition)...");

        // Config must be created and language set BEFORE the recognizer is built,
        // since SpeechRecognizer snapshots config properties at construction time.
        try (SpeechConfig speechConfig = SpeechConfig.fromSubscription(SPEECH_KEY, SPEECH_REGION)) {

            speechConfig.setSpeechRecognitionLanguage(RECOGNITION_LANGUAGE);

            try (AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
                 SpeechRecognizer recognizer = new SpeechRecognizer(speechConfig, audioConfig)) {

                System.out.println("\n[Listening] Speak into your microphone now...");

                SpeechRecognitionResult result = recognizer.recognizeOnceAsync().get();

                if (result.getReason() == ResultReason.RecognizedSpeech) {
                    System.out.println("\nSUCCESS: Text Captured!");
                    System.out.println("Result: \"" + result.getText() + "\"");
                    return result.getText();

                } else if (result.getReason() == ResultReason.NoMatch) {
                    System.out.println("NoMatch: Speech was heard but couldn't be parsed into text.");
                    return "";

                } else if (result.getReason() == ResultReason.Canceled) {
                    CancellationDetails cancellation = CancellationDetails.fromResult(result);
                    System.out.println("CANCELED: Interaction ended unexpectedly.");
                    System.out.println("Reason: " + cancellation.getReason());

                    if (cancellation.getReason() == CancellationReason.Error) {
                        System.err.println("Error Code: " + cancellation.getErrorCode());
                        System.err.println("Error Details: " + cancellation.getErrorDetails());
                    }
                }
            }

        } catch (InterruptedException | ExecutionException e) {
            System.err.println("An exception occurred during speech recognition processing.");
            e.printStackTrace();
        }
        return "";
    }

    /**
     * Synthesizes the given text to speech and plays it through the default speaker.
     * Returns true on success, false on failure.
     */
    public static boolean speak(String text) {
        return speak(text, SYNTHESIS_VOICE);
    }

    /**
     * Synthesizes the given text using a specific voice name and plays it through
     * the default speaker.
     */
    public static boolean speak(String text, String voiceName) {
        if (SPEECH_KEY == null || SPEECH_REGION == null) {
            System.err.println("Error: Missing AZURE_SPEECH_KEY or AZURE_SPEECH_REGION environment variables.");
            return false;
        }

        if (text == null || text.isEmpty()) {
            System.err.println("Error: No text provided to speak().");
            return false;
        }

        System.out.println("Initializing Azure Speech Service (synthesis)...");

        try (SpeechConfig speechConfig = SpeechConfig.fromSubscription(SPEECH_KEY, SPEECH_REGION)) {

            speechConfig.setSpeechSynthesisVoiceName(voiceName);

            // No AudioConfig passed -> defaults to the system's default speaker output.
            try (SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig)) {

                SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text).get();

                if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                    System.out.println("Speech synthesized successfully for text: \"" + text + "\"");
                    return true;

                } else if (result.getReason() == ResultReason.Canceled) {
                    SpeechSynthesisCancellationDetails cancellation =
                            SpeechSynthesisCancellationDetails.fromResult(result);
                    System.out.println("CANCELED: " + cancellation.getReason());

                    if (cancellation.getReason() == CancellationReason.Error) {
                        System.err.println("Error Code: " + cancellation.getErrorCode());
                        System.err.println("Error Details: " + cancellation.getErrorDetails());
                    }
                    return false;
                }
            }

        } catch (InterruptedException | ExecutionException e) {
            System.err.println("An exception occurred during speech synthesis processing.");
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Synthesizes speech and saves it to a WAV file instead of playing it live.
     */
    public static boolean speakToFile(String text, String outputPath) {
        return speakToFile(text, outputPath, SYNTHESIS_VOICE);
    }

    public static boolean speakToFile(String text, String outputPath, String voiceName) {
        if (SPEECH_KEY == null || SPEECH_REGION == null) {
            System.err.println("Error: Missing AZURE_SPEECH_KEY or AZURE_SPEECH_REGION environment variables.");
            return false;
        }

        try (SpeechConfig speechConfig = SpeechConfig.fromSubscription(SPEECH_KEY, SPEECH_REGION)) {

            speechConfig.setSpeechSynthesisVoiceName(voiceName);

            try (AudioConfig audioConfig = AudioConfig.fromWavFileOutput(outputPath);
                 SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig, audioConfig)) {

                SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text).get();

                if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                    System.out.println("Saved synthesized speech to: " + outputPath);
                    return true;
                } else if (result.getReason() == ResultReason.Canceled) {
                    SpeechSynthesisCancellationDetails cancellation =
                            SpeechSynthesisCancellationDetails.fromResult(result);
                    System.err.println("CANCELED: " + cancellation.getReason());
                    return false;
                }
            }

        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void main(String[] args) {
        // Example: listen, then echo back what was heard
        String heard = voice();
        if (!heard.isEmpty()) {
            speak("आपने कहा: " + heard);
        } else {
            speak("मुझे कुछ सुनाई नहीं दिया।");
        }
    }
}