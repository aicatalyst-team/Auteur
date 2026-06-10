package com.auteur.preset;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 视频形态预设。
 *
 * 一行 preset 完整描述"一种视频形态的所有配方":
 *   - 输入字段(input_schema_json)
 *   - 各 stage 的 prompt(brainstorm/script/critic/storyboard/assistant_director/bgm_mood)
 *   - 美术配置(模型/锁脸/参考图/风格后缀/负面词)
 *   - 录音配置(可空 = 无旁白)
 *   - 合成配置(画幅/水印/BGM/hook 段/Remotion composition)
 *
 * 加新内容形态 = 在 UI 里建一行 preset,代码不动。
 *
 * JSON 列用 String 存,配 @JsonRawValue 让 REST 直接吐 JSON,无需 jackson 二次序列化。
 */
@Entity
@Table(name = "preset")
@Getter
@Setter
public class Preset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 内部 key,如 'lifecopy' / 'freeform'。全局唯一,routing 用。 */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 'private' | 'public'。private 仅 owner_name 可见(软标记,无鉴权)。 */
    @Column(nullable = false, length = 16)
    private String visibility = "private";

    @Column(name = "owner_name", length = 64)
    private String ownerName;

    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    @Column(name = "brainstorm_prompt_yaml", columnDefinition = "TEXT")
    private String brainstormPromptYaml;

    @Column(name = "script_prompt_yaml", columnDefinition = "TEXT", nullable = false)
    private String scriptPromptYaml;

    @Column(name = "script_critic_prompt_yaml", columnDefinition = "TEXT")
    private String scriptCriticPromptYaml;

    @Column(name = "script_critic_threshold")
    private Integer scriptCriticThreshold;

    @Column(name = "storyboard_prompt_yaml", columnDefinition = "TEXT", nullable = false)
    private String storyboardPromptYaml;

    /** 'PRECISE_BY_CUE' | 'FREE'。PRECISE_BY_CUE 强制校验 anchor_cue_indices 严格连续覆盖。 */
    @Column(name = "storyboard_mode", nullable = false, length = 32)
    private String storyboardMode = "FREE";

    @Column(name = "assistant_director_prompt_yaml", columnDefinition = "TEXT")
    private String assistantDirectorPromptYaml;

    @Column(name = "bgm_mood_prompt_yaml", columnDefinition = "TEXT")
    private String bgmMoodPromptYaml;

    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "image_config_json", columnDefinition = "TEXT")
    private String imageConfigJson;

    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "voice_config_json", columnDefinition = "TEXT")
    private String voiceConfigJson;

    @Column(name = "bgm_enabled", nullable = false)
    private boolean bgmEnabled;

    /**
     * true = BGM 锁定模式:用户不让在 BgmPicker 里选曲,后端按 preset 兜底逻辑出曲。
     * 仅在 bgm_enabled=true 时有意义;bgm_enabled=false 时整段无 BGM,本字段忽略。
     */
    @Column(name = "bgm_locked", nullable = false)
    private boolean bgmLocked;

    @Column(name = "composition_id", nullable = false, length = 64)
    private String compositionId;

    @Column(name = "format_width", nullable = false)
    private Integer formatWidth;

    @Column(name = "format_height", nullable = false)
    private Integer formatHeight;

    /** null = 不加水印。 */
    @Column(name = "watermark_text", length = 128)
    private String watermarkText;

    @Column(name = "hook_segment_enabled", nullable = false)
    private boolean hookSegmentEnabled;

    /** Hook 段翻书音效 URL,配合 hookSegmentEnabled。空 = 不放音效。 */
    @Column(name = "hook_page_flip_sound_url", length = 500)
    private String hookPageFlipSoundUrl;

    /** Storyboard critic 要求的极特写最少镜数;0 = 不检查。 */
    @Column(name = "min_extreme_closeup", nullable = false)
    private int minExtremeCloseup = 0;

    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
