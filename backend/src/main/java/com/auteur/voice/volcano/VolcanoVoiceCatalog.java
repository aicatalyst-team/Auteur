package com.auteur.voice.volcano;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 火山预训练音色目录(精选)。来源:火山文档「在线音色列表」豆包语音合成 2.0。
 *
 * group: biography(生平叙述) / mystery(悬疑旁白) / both(通用)。
 */
@Component
@RequiredArgsConstructor
public class VolcanoVoiceCatalog {

    public record Voice(String voiceType, String label, String suit, String gender, String group) {}

    public static final List<Voice> VOICES = List.of(
            // 男声
            new Voice("zh_male_wennuanahu_uranus_bigtts",   "温暖阿虎", "温暖治愈/亲和叙述/陪伴感", "male", "both"),
            new Voice("zh_male_xuanyijieshuo_uranus_bigtts", "悬疑解说", "神秘/暗黑风/解说", "male", "mystery"),
            new Voice("zh_male_baqiqingshu_uranus_bigtts",  "霸气青叔",  "霸气/中年权臣/帝王重臣", "male", "biography"),
            new Voice("zh_male_qingcang_uranus_bigtts",     "擎苍",     "古风沧桑/武将/沙场老将", "male", "biography"),
            new Voice("zh_male_ruyaqingnian_uranus_bigtts", "儒雅青年", "文人/才子/谋士", "male", "biography"),
            new Voice("zh_male_gaolengchenwen_uranus_bigtts","高冷沉稳","帝王/老年回望/重大决策", "male", "biography"),
            new Voice("zh_male_shenyeboke_uranus_bigtts",   "深夜播客", "深沉/沉郁/落魄文人", "male", "both"),
            new Voice("zh_male_yuanboxiaoshu_uranus_bigtts","渊博小叔", "学者/大儒/智者讲述", "male", "biography"),
            new Voice("zh_male_cixingjieshuonan_uranus_bigtts","磁性解说男","磁性/史诗叙述", "male", "both"),
            new Voice("zh_male_yizhipiannan_uranus_bigtts", "译制片男", "译制片腔/戏剧化沧桑", "male", "biography"),
            new Voice("zh_male_dongfanghaoran_uranus_bigtts","东方浩然","豪迈/慷慨悲歌", "male", "biography"),
            new Voice("zh_male_fanjuanqingnian_uranus_bigtts","反卷青年","青壮年才俊/直率不羁", "male", "biography"),
            new Voice("zh_male_silang_uranus_bigtts",       "四郎",     "文艺青年/落魄才子", "male", "biography"),
            new Voice("zh_male_huolixiaoge_uranus_bigtts",  "活力小哥", "朝气青年/少年得志", "male", "biography"),

            // 女声
            new Voice("zh_female_gufengshaoyu_uranus_bigtts","古风少御","古风女声/名妓/才女", "female", "biography"),
            new Voice("zh_female_wenroushunv_uranus_bigtts","温柔淑女","大家闺秀/温婉女子", "female", "biography"),
            new Voice("zh_female_gaolengyujie_uranus_bigtts","高冷御姐","后妃/权后/冷艳女皇", "female", "biography"),
            new Voice("zh_female_zhixingnv_uranus_bigtts",  "知性女声", "才女/中年贵妇", "female", "biography"),
            new Voice("zh_female_wuzetian_uranus_bigtts",   "武则天",   "女皇专用/强势君主感", "female", "biography"),
            new Voice("zh_female_xinlingjitang_uranus_bigtts","心灵鸡汤","温暖叙述/母性", "female", "both"),
            new Voice("zh_female_tvbnv_uranus_bigtts",      "TVB女声",  "港式叙述/成熟女", "female", "both"),
            new Voice("zh_female_meilinvyou_uranus_bigtts", "魅力女友", "妩媚/风情万种", "female", "biography"),
            new Voice("zh_female_wenrouxiaoya_uranus_bigtts","温柔小雅","婉约/内敛少妇", "female", "biography"),
            new Voice("zh_female_roumeinvyou_uranus_bigtts","柔美女友","柔美/温柔早逝", "female", "biography"),
            new Voice("zh_female_qiaopinv_uranus_bigtts",   "俏皮女声", "活泼少女/公主", "female", "biography"),
            // emo v2 系列(_mars_bigtts):情感更丰富
            new Voice("zh_female_shuangkuaisisi_emo_v2_mars_bigtts", "爽快思思", "明亮爽朗/年轻女声/当代叙述", "female", "both")
    );

    public List<Voice> all() {
        return VOICES;
    }

    public boolean has(String voiceType) {
        if (voiceType == null) return false;
        return VOICES.stream().anyMatch(v -> v.voiceType().equals(voiceType));
    }

    /** catalog 兜底默认音色:温暖阿虎。preset.voice_config_json.voiceId 没指定时用。 */
    public String defaultVoice() {
        return "zh_male_wennuanahu_uranus_bigtts";
    }
}
