// 火山引擎豆包 TTS 音色库（与后端 VolcanoVoiceCatalog 严格对齐）
// voice_type 格式：zh_{gender}_{name}_uranus_bigtts

export interface VoiceOption {
  value: string
  label: string
  suit?: string
}

export interface VoiceGroup {
  label: string
  voices: VoiceOption[]
}

/** Catalog 兜底默认音色（温暖阿虎），与后端 VolcanoVoiceCatalog.defaultVoice() 对齐。preset.voice_config 没指定时用。 */
export const DEFAULT_VOICE = 'zh_male_wennuanahu_uranus_bigtts'

export const VOICE_GROUPS: VoiceGroup[] = [
  {
    label: '推荐 · 旁白解说',
    voices: [
      { value: 'zh_male_wennuanahu_uranus_bigtts',     label: '温暖阿虎',  suit: '温暖治愈/亲和叙述/陪伴感(默认)' },
      { value: 'zh_male_xuanyijieshuo_uranus_bigtts', label: '悬疑解说', suit: '神秘/暗黑风/解说' },
      { value: 'zh_male_shenyeboke_uranus_bigtts',    label: '深夜播客', suit: '深沉/沉郁/落魄文人' },
      { value: 'zh_male_cixingjieshuonan_uranus_bigtts', label: '磁性解说男', suit: '磁性/史诗叙述' },
    ],
  },
  {
    label: '推荐 · 第一视角 · 男声',
    voices: [
      { value: 'zh_male_gaolengchenwen_uranus_bigtts', label: '高冷沉稳',  suit: '帝王/老年回望/重大决策' },
      { value: 'zh_male_baqiqingshu_uranus_bigtts',   label: '霸气青叔',  suit: '霸气/中年权臣/帝王重臣' },
      { value: 'zh_male_qingcang_uranus_bigtts',      label: '擎苍',      suit: '古风沧桑/武将/沙场老将' },
      { value: 'zh_male_ruyaqingnian_uranus_bigtts',  label: '儒雅青年',  suit: '文人/才子/谋士' },
      { value: 'zh_male_yuanboxiaoshu_uranus_bigtts', label: '渊博小叔',  suit: '学者/大儒/智者讲述' },
      { value: 'zh_male_yizhipiannan_uranus_bigtts',  label: '译制片男',  suit: '译制片腔/戏剧化沧桑' },
      { value: 'zh_male_dongfanghaoran_uranus_bigtts', label: '东方浩然', suit: '豪迈/慷慨悲歌' },
      { value: 'zh_male_fanjuanqingnian_uranus_bigtts', label: '反卷青年', suit: '青壮年才俊/直率不羁' },
      { value: 'zh_male_silang_uranus_bigtts',        label: '四郎',      suit: '文艺青年/落魄才子' },
      { value: 'zh_male_huolixiaoge_uranus_bigtts',   label: '活力小哥',  suit: '朝气青年/少年得志' },
    ],
  },
  {
    label: '推荐 · 第一视角 · 女声',
    voices: [
      { value: 'zh_female_gaolengyujie_uranus_bigtts', label: '高冷御姐', suit: '后妃/权后/冷艳女皇' },
      { value: 'zh_female_wuzetian_uranus_bigtts',     label: '武则天',   suit: '女皇专用/强势君主感' },
      { value: 'zh_female_gufengshaoyu_uranus_bigtts', label: '古风少御', suit: '古风女声/名妓/才女' },
      { value: 'zh_female_wenroushunv_uranus_bigtts',  label: '温柔淑女', suit: '大家闺秀/温婉女子' },
      { value: 'zh_female_zhixingnv_uranus_bigtts',    label: '知性女声', suit: '才女/中年贵妇' },
      { value: 'zh_female_meilinvyou_uranus_bigtts',   label: '魅力女友', suit: '妩媚/风情万种' },
      { value: 'zh_female_wenrouxiaoya_uranus_bigtts', label: '温柔小雅', suit: '婉约/内敛少妇' },
      { value: 'zh_female_roumeinvyou_uranus_bigtts',  label: '柔美女友', suit: '柔美/温柔早逝' },
      { value: 'zh_female_qiaopinv_uranus_bigtts',     label: '俏皮女声', suit: '活泼少女/公主' },
    ],
  },
  {
    label: '通用',
    voices: [
      { value: 'zh_female_xinlingjitang_uranus_bigtts', label: '心灵鸡汤', suit: '温暖叙述/母性' },
      { value: 'zh_female_tvbnv_uranus_bigtts',         label: 'TVB女声',  suit: '港式叙述/成熟女' },
      { value: 'zh_female_shuangkuaisisi_emo_v2_mars_bigtts', label: '爽快思思', suit: '明亮爽朗/年轻女声/当代叙述 · 豆包 emo v2' },
    ],
  },
]

/** 给定音色 ID，反查中文显示名；不在表里就原样返回 ID */
export function voiceLabelOf(id: string | null | undefined): string {
  if (!id) return ''
  for (const g of VOICE_GROUPS) {
    for (const v of g.voices) {
      if (v.value === id) return v.label
    }
  }
  return id
}
