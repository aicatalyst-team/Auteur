package com.auteur.preset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Preset 管理 REST API。
 *
 * 路径约定:
 *   GET    /api/presets                  列预设(根据 X-Auteur-Admin 头过滤,公开/私有可见性)
 *   GET    /api/presets/{id}             读单个
 *   POST   /api/presets                  创建
 *   PUT    /api/presets/{id}             覆盖当前版(不写 snapshot)
 *   POST   /api/presets/{id}/save-version  写一份 snapshot + currentVersion+1 + apply 改动
 *   POST   /api/presets/{id}/rollback?version=N   回滚到历史快照
 *   DELETE /api/presets/{id}             删除(级联删 version + asset)
 *   GET    /api/presets/{id}/versions    历史版本列表
 *   GET    /api/presets/{id}/assets      关联资源列表
 *   POST   /api/presets/{id}/optimize    "沟通优化":LLM 根据用户反馈重新生成某 section 字段(不落库)
 *
 * 可见性靠"软"协议:浏览器请求带 X-Auteur-Admin: 1 时返回私有 + 公开;否则只返公开。
 * 这不是真鉴权,只是 UI 层隔离 — 与"无部署/不上公网"威胁模型自洽(见 PRESET_REFACTOR_PLAN.md §〇)。
 */
@Slf4j
@RestController
@RequestMapping("/api/presets")
@RequiredArgsConstructor
public class PresetController {

    private final PresetService presetService;
    private final PresetOptimizeService presetOptimizeService;

    @GetMapping
    public List<Preset> list(
            @RequestHeader(value = "X-Auteur-Admin", required = false) String adminHeader,
            @RequestHeader(value = "X-Auteur-Owner", required = false) String ownerName
    ) {
        boolean adminMode = "1".equals(adminHeader);
        return presetService.listVisible(adminMode, ownerName);
    }

    @GetMapping("/{id}")
    public Preset get(@PathVariable Long id) {
        return presetService.get(id);
    }

    @GetMapping("/by-name/{name}")
    public Preset getByName(@PathVariable String name) {
        return presetService.getByName(name);
    }

    @PostMapping
    public Preset create(@RequestBody Preset draft) {
        return presetService.create(draft);
    }

    @PutMapping("/{id}")
    public Preset update(@PathVariable Long id, @RequestBody Preset patch) {
        return presetService.update(id, patch);
    }

    @PostMapping("/{id}/save-version")
    public Preset saveAsNewVersion(
            @PathVariable Long id,
            @RequestBody Preset patch,
            @RequestParam(required = false) String comment
    ) {
        return presetService.saveAsNewVersion(id, patch, comment);
    }

    @PostMapping("/{id}/rollback")
    public Preset rollback(@PathVariable Long id, @RequestParam Integer version) {
        return presetService.rollback(id, version);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        presetService.delete(id);
    }

    @GetMapping("/{id}/versions")
    public List<PresetVersion> versions(@PathVariable Long id) {
        return presetService.listVersions(id);
    }

    @GetMapping("/{id}/assets")
    public List<PresetAsset> assets(@PathVariable Long id) {
        return presetService.listAssets(id);
    }

    /**
     * "沟通优化"端点:用户在编辑器某一节描述对当前配置的不满,LLM 返回该节字段的重新生成结果。
     * 不落库,仅返回新值,前端写回 draft,让用户决定是否保存。
     */
    @PostMapping("/{id}/optimize")
    public PresetOptimizeService.OptimizeResponse optimize(
            @PathVariable Long id,
            @RequestBody PresetOptimizeService.OptimizeRequest req
    ) {
        return presetOptimizeService.optimize(id, req);
    }
}
