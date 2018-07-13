package etri.etriopenasr;

import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.google.gson.Gson;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.json.*;

public class MainActivity extends AppCompatActivity {
    private static final String MSG_KEY = "status";

    //    private static Button buttonStart;
    private static ImageButton buttonStart;
    private static TextView textResult;

    private static String result;
    private static JSONObject resultObj;

    // 최대 녹음길이
    int maxLenSpeech = 16000 * 45;
    // 녹음한 데이터가 담길 배열
    byte[] speechData = new byte[maxLenSpeech * 2];
    int lenSpeech = 0;
    boolean isRecording = false;
    boolean forceStop = false;

    private final MyHandler handler = new MyHandler();

    private static class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle bd = msg.getData();
            String v = bd.getString(MSG_KEY);
            switch (msg.what) {
                // 녹음이 시작되었음(버튼)
                case 1:
                    textResult.setText(v);
//                    buttonStart.setText("PUSH TO STOP");
                    break;
                // 녹음이 정상적으로 종료되었음(버튼 또는 max time)
                case 2:
                    textResult.setText(v);
                    buttonStart.setEnabled(false);
                    break;
                // 녹음이 비정상적으로 종료되었음(마이크 권한 등)
                case 3:
                    textResult.setText(v);
//                    buttonStart.setText("PUSH TO START");
                    break;
                // 인식이 비정상적으로 종료되었음(timeout 등)
                case 4:
                    textResult.setText(v);
                    buttonStart.setEnabled(true);
//                    buttonStart.setText("PUSH TO START");
                    break;
                // 인식이 정상적으로 종료되었음 (thread내에서 exception포함)
                case 5:
                    Log.e("결과 : ", resultObj.toString());
                    textResult.setText(getQuestionAndAnswer(resultObj));
                    buttonStart.setEnabled(true);
//                    buttonStart.setText("PUSH TO START");
                    break;
            }
            super.handleMessage(msg);
        }
    }

    private static String getQuestionAndAnswer(JSONObject obj) {
        String recognition = "";

        try {
            recognition = resultObj.getJSONObject("return_object").getString("recognized");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (recognition.contains("답은")) {
            String question = recognition.substring(0, recognition.indexOf("답은"));
            String answer = recognition.substring(recognition.indexOf("답은"));
            if (question.matches(".*\\d+.*") && answer.matches(".*\\d+.*")) {
                question = question.replaceAll("\\D+", "");
                answer = answer.replaceAll("\\D+", "");
                return "문제번호 : " + question + "\n답 : " + answer;
            } else {
                return "인식결과: " + recognition + "\n올바른 형식(X번의 답은 X번)으로 말해주세요.";
            }
        }
        return "올바른 형식(X번의 답은 X번)으로 말해주세요.";
    }

    public void SendMessage(String str, int id) {
        Message msg = handler.obtainMessage();
        Bundle bd = new Bundle();
        bd.putString(MSG_KEY, str);
        msg.what = id;
        msg.setData(bd);
        handler.sendMessage(msg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        buttonStart = (Button) findViewById(R.id.buttonStart);
        buttonStart = (ImageButton) findViewById(R.id.buttonStart);
        textResult = (TextView) findViewById(R.id.textResult);

        buttonStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // isRecording은 원래 false.
                if (isRecording) {
                    // 녹음중인 상태에서 다시 버튼 클릭시 녹음 강제종료
                    forceStop = true;
                } else {
                    try {
                        new Thread(new Runnable() {
                            public void run() {
                                SendMessage("Recording...", 1);
                                try {
                                    // 녹음
                                    recordSpeech();
                                    SendMessage("Recognizing...", 2);
                                } catch (RuntimeException e) {
                                    SendMessage(e.getMessage(), 3);
                                    return;
                                }

                                Thread threadRecog = new Thread(new Runnable() {
                                    public void run() {
                                        result = sendDataAndGetResult();
                                        try {
                                            resultObj = new JSONObject(result);
                                        } catch (JSONException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                });

                                // 결과 얻는 스레드를 시작
                                threadRecog.start();
                                try {
                                    threadRecog.join(20000);
                                    if (threadRecog.isAlive()) {
                                        threadRecog.interrupt();
                                        SendMessage("No response from server for 20 secs", 4);
                                    } else {
                                        SendMessage("OK", 5);
                                    }
                                } catch (InterruptedException e) {
                                    SendMessage("Interrupted", 4);
                                }
                            }
                        }).start();
                    } catch (Throwable t) {
                        textResult.setText("ERROR: " + t.toString());
                        forceStop = false;
                        isRecording = false;
                    }
                }
            }
        });
    }

    public static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in),1000);
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            sb.append(line);
        }
        in.close();
        return sb.toString();
    }

    public void recordSpeech() throws RuntimeException {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                    16000, // sampling frequency
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord audio = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, // sampling frequency
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            lenSpeech = 0;

            // 오디오 권한 안줬을때 에러
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("ERROR: Failed to initialize audio device. Allow app to access microphone");
            } else {
                short[] inBuffer = new short[bufferSize];
                forceStop = false;
                // 버튼 다시 클릭시에도 forceStop이 true로.
                isRecording = true;
                audio.startRecording();
                while (!forceStop) {
                    int ret = audio.read(inBuffer, 0, bufferSize);
                    for (int i = 0; i < ret; i++) {
                        if (lenSpeech >= maxLenSpeech) {
                            // 혹은 최대 길이를 넘어가도 forceStop
                            forceStop = true;
                            break;
                        }
                        speechData[lenSpeech*2] = (byte)(inBuffer[i] & 0x00FF);
                        speechData[lenSpeech*2+1] = (byte)((inBuffer[i] & 0xFF00) >> 8);
                        lenSpeech++;
                    }
                }
                audio.stop();
                audio.release();
                isRecording = false;
            }
        } catch (Throwable t) {
            throw new RuntimeException(t.toString());
        }
    }

    public String sendDataAndGetResult() {
        String openApiURL = "http://aiopen.etri.re.kr:8000/WiseASR/Recognition";
        // api key
        String accessKey = "";
        String audioContents;

        // 자바 객체를 JSON으로 변환해주는 라이브러리 by Google
        Gson gson = new Gson();

        Map<String, Object> request = new HashMap<>();
        Map<String, String> argument = new HashMap<>();

        audioContents = Base64.encodeToString(
                speechData, 0, lenSpeech*2, Base64.NO_WRAP);

        // 한국어인식
        argument.put("language_code", "korean");
        argument.put("audio", audioContents);

        request.put("access_key", accessKey);
        request.put("argument", argument);

        URL url;
        Integer responseCode;
        String responBody;
        try {
            url = new URL(openApiURL);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);

            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            // 요청을 gson으로 감싸서 전달
            wr.write(gson.toJson(request).getBytes("UTF-8"));
            wr.flush();
            wr.close();

            responseCode = con.getResponseCode();
            if (responseCode == 200) {
                InputStream is = new BufferedInputStream(con.getInputStream());
                responBody = readStream(is);
                return responBody;
            }
            else
                return "ERROR: " + Integer.toString(responseCode);
        } catch (Throwable t) {
            return "ERROR: " + t.toString();
        }
    }
}
