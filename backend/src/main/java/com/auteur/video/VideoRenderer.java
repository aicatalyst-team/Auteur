package com.auteur.video;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/**
 * 视频合成抽象。实现包括 ffmpeg / Remotion / mock。
 */
public interface VideoRenderer {

    Result render(Request req);

    /**
     * 单镜片段:一张静态图 + 该镜的播放时长。
     * sectionCode 用于章节断点检测(Remotion 路径消费),老 ffmpeg 路径忽略此字段,可空。
     * anchorText 是该 shot 的脚本字面锚定短语(6-15 字),Remotion 路径用作 motion intent 启发式输入,
     * 把高潮/转场/凝视等情绪映射到对应运镜池;ffmpeg 路径忽略,可空。
     */
    record ImageClip(int shotIndex, String imageUrl,
                     double startSec, double durationSec, String caption,
                     String sectionCode, String anchorText) {}

    /**
     * BGM 配置。bgmFile 本地 mp3 路径(ffmpeg 用),httpUrl 同曲的 https URL(Remotion 浏览器加载用,
     * 不能用 file://),volume 0.05~0.6。整片单曲循环 + sidechain ducking(仅 ffmpeg 路径)。
     */
    record BgmConfig(Path bgmFile, String httpUrl, double volume) {}

    /**
     * 开头 hook 段配置(由 preset.hook_segment_enabled 启用)。所有字段都准备好才生效,任一缺失走"无 hook"路径。
     *
     * @param imageUrls          快切素材(典型 12 张)
     * @param audioUrl           hook 段独立 mp3 URL
     * @param text               hook 段标题文本(通常 = topic.title)
     * @param durationSec        hook 段总时长,主体 shots 整体后推
     * @param pageFlipSoundUrl   翻书音效 mp3 URL,可空。每张图切换时叠播。
     */
    record HookConfig(java.util.List<String> imageUrls, String audioUrl, String text,
                      double durationSec, String pageFlipSoundUrl) {}

    /**
     * @param subtitleStyle "standard" 走 SRT force_style 全白字幕;"highlight" 走 ASS 关键词加色加粗。
     * @param compositionId Remotion composition 名(由 preset.composition_id 决定;Remotion 路径必填,ffmpeg 路径可空)。
     * @param watermarkText 左上角水印文本(由 preset.watermark_text 决定);null/空 = 不加水印。ffmpeg 路径忽略。
     */
    record Request(Long scriptId, List<ImageClip> clips, String audioUrl, String subtitleUrl,
                   String format, int width, int height, BgmConfig bgm, String subtitleStyle,
                   String contentType, String personaJson, String protagonistRefUrl, Long topicId,
                   HookConfig hook, String compositionId, String watermarkText) {}

    record Result(String videoUrl, Integer durationSeconds, Integer width, Integer height,
                  String format, BigDecimal costYuan) {}
}
