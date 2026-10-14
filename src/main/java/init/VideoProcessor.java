package init;

import java.nio.file.Paths;
import java.util.Map;

import javax.json.Json;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;

public class VideoProcessor implements RequestHandler<Map<String, String>, String> {

     private final String ffmpegPath = "/opt/ffmpeg";
     private final String ffprobePath = "/opt/ffprobe";
     private final String output = "/tmp";
     private S3Utils s3Utils;

     private LambdaLogger logger;
     private String awsRequestId;

     @Override
     public String handleRequest(Map<String, String> input, Context context) {
          try {
               final String username = input.get("username");
               final String filename = input.get("filename");
               final String url = input.get("url");

               this.s3Utils = new S3Utils(context.getAwsRequestId(), context.getLogger());

               this.awsRequestId = context.getAwsRequestId();
               this.logger = context.getLogger();
               
               final String mp4Url = convertAviToLossless(username, filename, url);
               final String[] thumbnailResults = createVideoThumbnail(username, filename, mp4Url);

               final String thumbnail = thumbnailResults[0];
               final String cropThumbnail = thumbnailResults[1];
               final String card = convertVideo(username, filename, mp4Url);

               s3Utils.deleteObject(username, filename + ".converted.avi");

               if (thumbnail == null || card == null) {
                    return null;
               } else {
                    String result = Json.createObjectBuilder()
                         .add("status", "success")
                         .add("thumbnail", thumbnail)
                         .add("cropThumbnail", cropThumbnail)
                         .add("card", card)
                         .build().toString();

                    return result;
               }

          } catch (Exception e) {
               String result = Json.createObjectBuilder()
                         .add("status", "fail")
                         .add("errorMessage", e.getMessage())
                         .build().toString();

                    return result;
          }
     }

     public String[] createVideoThumbnail(String username, String filename, String rawVideoUrl) {
          try {
               logger.log("[" + awsRequestId + "] 비디오 섬네일 생성 시작.\n");

               final FFmpeg ffmpeg = new FFmpeg(ffmpegPath);
               final FFprobe ffprobe = new FFprobe(ffprobePath);

               FFmpegProbeResult probeResult = ffprobe.probe(rawVideoUrl);
               FFmpegStream stream = probeResult.getStreams().get(0);

               String taller = stream.width > stream.height ? "width" : "height";
               int finalWidth;
               int finalHeight;

               logger.log("[" + awsRequestId + "] 오리지널스: " + stream.width + " :: " + stream.height + "\n");

               if (taller.equals("width")) {
                    float ratio = (float) stream.width / (float) stream.height;
                    finalWidth = (int) (ratio * 450.0f);
                    finalHeight = 450;
               } else {
                    float ratio = (float) stream.height / (float) stream.width;
                    finalWidth = 450;
                    finalHeight = (int) (ratio * 450.0f);
               }

               logger.log("[" + awsRequestId + "] 섬네일 최종 사이즈 : " + finalWidth + " :: " + finalHeight + "\n");

               FFmpegBuilder thumbnailBuilder = new FFmpegBuilder()
                    .setInput(probeResult)
                    .overrideOutputFiles(true)
                    .addOutput(Paths.get(output, filename + ".thumb.jpg").toAbsolutePath().toString())
                         .addExtraArgs("-ss", "00:00:00.000")
                         .setFrames(1)
                         .setVideoBitRate(10240)
                         .setAudioBitRate(10240)
                         // .setConstantRateFactor(factor)
                         .setVideoResolution(finalWidth, finalHeight)
                         .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL)
                         .done();

               FFmpegExecutor thumbnailExecutor = new FFmpegExecutor(ffmpeg, ffprobe);

               // Run a one-pass encode
               thumbnailExecutor.createJob(thumbnailBuilder).run();
               
               final String cropThumbnail = cropThumbnailImage(username, filename + ".thumb", finalWidth, finalHeight);
               // return this.cropThumbnailImage(username, filename + ".thumb.jpg", finalWidth, finalHeight);
               return new String[]{ s3Utils.upload(username, Paths.get(output, filename + ".thumb.jpg")), cropThumbnail };

          } catch (Exception e) {
               logger.log("[" + awsRequestId + "] 섬네일 생성 실패. [" + username + " : " + filename + "]\n");
               logger.log("[" + awsRequestId + "] Original File URL : " + rawVideoUrl + "\n");
               e.printStackTrace();
               return null;
          }
     }
     
     public String cropThumbnailImage(String username, String filename, int finalWidth, int finalHeight) {
          try {
               logger.log("[" + awsRequestId + "] Crop 작업 시작.\n");

               final FFmpeg ffmpeg = new FFmpeg(ffmpegPath);
               final FFprobe ffprobe = new FFprobe(ffprobePath);

               int least = finalWidth < finalHeight ? finalWidth : finalHeight;
               int size = least < 450 ? least : 450;

               logger.log("[" + awsRequestId + "] Crop 최종 사이즈 : " + size + "\n");

               FFmpegBuilder cropBuilder = new FFmpegBuilder()
                         .setInput(ffprobe.probe(Paths.get(output, filename + ".jpg").toString()))
                         .overrideOutputFiles(true)
                         .addOutput(Paths.get(output, filename + "-crop.jpg").toString())
                              .addExtraArgs("-filter:v", "crop=" + size + ":" + size)
                              .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL)
                              .done();
     
               FFmpegExecutor cropExecutor = new FFmpegExecutor(ffmpeg, ffprobe);

               // Run a one-pass encode
               cropExecutor.createJob(cropBuilder).run();

               return s3Utils.upload(username, Paths.get(output,  filename + "-crop.jpg"));

          } catch (Exception e) {
               logger.log("[" + awsRequestId + "] Crop 작업 실패 [" + username + " : " + filename + "]\n");
               e.printStackTrace();
               return null;
          }
     }
     
     public String convertVideo(String username, String filename, String rawVideoUrl) {
          try {
               logger.log("[" + awsRequestId + "] 비디오 컨버팅 시작.\n");

               final FFmpeg ffmpeg = new FFmpeg(ffmpegPath);
               final FFprobe ffprobe = new FFprobe(ffprobePath);

               FFmpegProbeResult probeResult = ffprobe.probe(rawVideoUrl);
               FFmpegStream stream = probeResult.getStreams().get(0);

               String taller = stream.width > stream.height ? "width" : "height";
               int finalWidth;
               int finalHeight;

               if (taller.equals("width")) {
                    float ratio = (float) stream.width / (float) stream.height;
                    finalWidth = (int) (ratio * 450.0f);
                    finalHeight = 450;
               } else {
                    float ratio = (float) stream.height / (float) stream.width;
                    finalWidth = 450;
                    finalHeight = (int) (ratio * 450.0f);
               }

               if (finalWidth % 2 != 0)
                    finalWidth += 1;

               if (finalHeight % 2 != 0) {
                    finalHeight += 1;
               }

               logger.log("[" + awsRequestId + "] 최종 비디오 사이즈 : " + finalWidth + " :: " + finalHeight + "\n");


               /**
                * 주석이... 끔직하게 되어있습니다.
                * 
                * 이 주석들은 제가 계속 보면서 학습하고 최적의 리사이징 결과물을 얻기 위해 지우지 않고 그대로 두었습니다. :)
                */
               FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(probeResult)
                    .overrideOutputFiles(true)
                    
                    .addOutput(Paths.get(output, filename + ".mp4").toString())
                         .setFormat("MP4")
                         // .setTargetSize(250_000)
                         // .addExtraArgs("-c", "copy")
                         // .disableSubtitle()
                         // .setVideoCodec("libx264")
                         // .setAudioCodec("aac")
                         // .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL).done();


                         // .setVideoCodec("mpeg4")
                         // .setVideoFilter("format=yuv444p,scale=" + finalWidth + ":" + finalHeight)
                         // .setVideoFrameRate(30, 1)
                         // .setVideoBitRate(512000)
                         // .setAudioBitRate(512000)
                         // .setVideoResolution(finalWidth, finalHeight)
                         // .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL).done();

                         // .setVideoCodec("libx265")
                         // // .setVideoCodec("hevc")
                         // // .setPreset("medium")
                         // .setVideoFilter("format=yuv444p,scale=" + finalWidth + ":" + finalHeight + ":flags=lanczos")
                         // .setVideoFrameRate(30, 1)
                         // .setVideoBitRate(512000)
                         // .setVideoResolution(finalWidth, finalHeight)
                         
                         // .setAudioBitRate(512000)
                         // .setStrict(FFmpegBuilder.Strict.NORMAL).done();

                         // .setAudioChannels(1)
                         // .setAudioCodec("aac")
                         // .setAudioBitRate(10240)

                         .setVideoCodec("libx264")
                         // .addExtraArgs("-profile:v", "baseline") // or high
                         .addExtraArgs("-level", "3.0")
                         // .addExtraArgs("-refs", "1")
                         // .addExtraArgs("-bufsize", "10240")
                         .addExtraArgs("-maxrate", "10240")
                         .setVideoMovFlags("faststart")
                         // .addExtraArgs("-vlevel", "4.0")
                         .setVideoFilter("scale=" + finalWidth + ":" + finalHeight + ":flags=lanczos")
                         .setVideoFrameRate(stream.avg_frame_rate)
                         .setVideoBitRate(10240)
                         .setConstantRateFactor(21)
                         // .setVideoBitRate(500000)
                         // .setVideoPixelFormat("yuv420p")
                         // .addExtraArgs("-x264opts", "colormatrix=bt709")
                         .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL).done();

               FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);

               // Run a one-pass encode
               executor.createJob(builder).run();
               return s3Utils.upload(username, Paths.get(output, filename + ".mp4"));

          } catch (Exception e) {
               logger.log("[" + awsRequestId + "] 비디오 컨버팅 실패. [" + username + " : " + filename + "]\n");
               logger.log("[" + awsRequestId + "] Original File URL : " + rawVideoUrl + "\n");
               e.printStackTrace();
               return null;
          }
     }

     public String convertAviToLossless(String username, String filename, String rawVideoUrl) {
          try {
               logger.log("[" + awsRequestId + "] MOV -> AVI 변환 작업 시작.\n");

               final FFmpeg ffmpeg = new FFmpeg(ffmpegPath);
               final FFprobe ffprobe = new FFprobe(ffprobePath);

               FFmpegProbeResult probeResult = ffprobe.probe(rawVideoUrl);
               FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(probeResult)
                    .overrideOutputFiles(true)
                    .addOutput(Paths.get(output, filename + ".converted.avi").toString())
                         .setFormat("avi")
                         // .setVideoCodec("mpeg4")
                         // .setConstantRateFactor(0)
                         // .addExtraArgs("-c", "copy")
                         // .setAudioCodec("aac")
                         // .addExtraArgs("-an")
                         .addExtraArgs("-qscale", "0")
                         .addExtraArgs("-colorspace", "bt709")
                         // .addExtraArgs("-x264opts", "colormatrix=bt709")
                         // .addExtraArgs("-vf", "eq=brightness=1.00:saturation=2")
                         .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL).done();

               FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);

               // Run a one-pass encode
               executor.createJob(builder).run();
               return s3Utils.upload(username, Paths.get(output, filename + ".converted.avi"));

          } catch (Exception e) {
               logger.log("[" + awsRequestId + "] MOV -> MP4 변환 작업 실패. [" + username + " : " + filename + "]\n");
               logger.log("[" + awsRequestId + "] Original File URL : " + rawVideoUrl + "\n");
               e.printStackTrace();
               return null;
          }
     }
}
