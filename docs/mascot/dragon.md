---
title: 奶屁龙
description: 奶屁龙的像素参考、矢量路径、配色与动作规格。
---

# 奶屁龙

一只没有翅膀、圆肚子、短腿的穴居小龙。它的特点不是“随便一只薄荷色龙”，而是下面这套固定的轮廓与五官。

## 形象基准

<div class="mascot-pair">
  <figure><img src="/mascots/dragon/reference.png" alt="已选定的奶屁龙像素参考原图" width="1312" height="1199" /><figcaption>原始参考 · 轮廓与比例依据</figcaption></figure>
  <figure><img src="/mascots/dragon/base.svg" alt="按参考图描出的奶屁龙纯路径 SVG" width="966" height="848" /><figcaption>纯路径 SVG · 可分层编辑</figcaption></figure>
</div>

- 头和身体连成圆滚轮廓，不画细长脖子，不改成直立瘦龙。
- 两只短象牙角；额头是浅色阶梯形 N 标记，不贴字体字符代替。
- 两只深色方眼，位置略有高低；小嘴、粉色舌头。
- 浅色肚皮和短爪，蓝色背鳍。
- 短而向外伸出的尾巴，末端是蓝色折线鳍；不能缩成一根细线。
- 不添加翅膀、耳朵、衣服、头发或眼镜；故事中的道具不能替代身体特征。

### 固定色板

| 用途 | 色值 |
| --- | --- |
| 主体薄荷 | `#91DACD` |
| 身体阴影 | `#59AAA6` |
| 角、腹部、爪尖与 N 标记 | `#FFF3D7` |
| 浅色部位阴影 | `#F3DFB7` |
| 背鳍与尾鳍 | `#246FE2` |
| 蓝色阴影 | `#1657B8` |
| 描边与五官 | `#15212B` |
| 舌头与小范围腮红 | `#FB9B92` |

SVG 对原图做平涂与少量阴影归并，保留主体轮廓和比例；不声称与包含柔和渐变的原图逐像素相同。不要为了减小文件而重画成另一套比例。

## SVG 与图层

[下载标准 SVG](/mascots/dragon/base.svg) · [下载原始参考 PNG](/mascots/dragon/reference.png)

标准 SVG 的 `viewBox` 为 `225 206 966 848`。原图的透明留白不算身体尺寸；横纵必须使用同一缩放比例。文件只有矢量路径，没有 `<image>`、外链位图或 Base64 图片。

| 图层 ID | 作用 |
| --- | --- |
| `body` | 身体主轮廓与阴影 |
| `tail` / `spines` | 尾巴与背鳍 |
| `horns` / `forehead` | 双角与 N 标记 |
| `belly` / `feet` | 肚皮与脚 |
| `paw-left` / `paw-right` | 两只前爪，可分别控制 |
| `eye-left` / `eye-right` / `mouth` | 可替换的五官 |
| `face-underlay` | 五官隐藏后填补底色，防止出现透明洞 |

母版沿参考图描出像素边界，以 4 个源坐标单位为最小描图步长，使用 `shape-rendering="crispEdges"`。这不是强行压成 32×32 的图标；不能为了符合某个网格把脸、角和尾巴挤变形。

小尺寸导出使用等比例缩放与最近邻采样。64px 是后续软件使用的候选宽度，不是已完成的软件接入。24px 等极小尺寸需要单独做简化稿并重新审核，不能宣称缩小后全部特征仍清晰。

## 多视图补全

<img src="/mascots/dragon/turnaround.svg" alt="奶屁龙正面、侧面、背面补全" width="648" height="176" />

[正面](/mascots/dragon/front.svg) · [侧面](/mascots/dragon/side.svg) · [背面](/mascots/dragon/back.svg)

这些是基于已选参考补全的结构稿，不替换上方标准姿态。正面保留双角、方眼与腹部；侧面只露出近侧眼睛，尾巴在身后；背面不出现眼睛、嘴、额头 N 或腹部斑块，背鳍沿背部排列。不能把正面镜像一下充当背面。

## 动作与表情

<div class="mascot-pair">
  <figure><img src="/mascots/dragon/ready.svg" alt="奶屁龙双爪动作结构样张" width="966" height="848" /><figcaption>双爪动作 · 结构验证，不是实际运行状态</figcaption></figure>
  <figure><img src="/mascots/dragon/sleep.svg" alt="闭眼休息的奶屁龙" width="966" height="848" /><figcaption>休息 · 停止不是错误</figcaption></figure>
</div>

动作从独立图层开始，而不是拉伸整张图片。样张用两只爪子的小幅错峰位移说明结构；后续正式动作还需要补画关节遮挡和连接处，不能直接把它当作成品动画。

建议每次动作保留停顿，位移落在像素步长上。页面不可见时暂停；减少动画设置使用静态帧。旋转、呼吸缩放、模糊发光和全身弹性形变不作为默认动作。

如果以后接入软件，表现只能跟随实际状态：准备、启动、运行、停止中、停止、失败、未知分别设计。运行不等于网站一定可达；未知不能演成停止；红色只用于真实失败，并保留文字说明。本阶段不实现这些业务接入。
