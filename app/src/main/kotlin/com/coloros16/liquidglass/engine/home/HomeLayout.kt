package com.coloros16.liquidglass.engine.home

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 桌面网格 / Dock / 页点几何：与系统桌面同一套换算，纯 JVM。
 *
 * 所有 dp 常量取自系统桌面资源，计算顺序对齐系统 DeviceProfile 构造链。
 */
object HomeLayout {

    const val COLUMNS = 4
    const val ROWS = 6
    const val HOTSEAT_ICONS = 4

    const val ICON_SIZE_DP = 56f
    const val ICON_TEXT_SP = 12f
    const val ICON_DRAWABLE_PADDING_DP = 4f
    const val CELL_PADDING_HOR_DP = 16f
    const val CELL_PADDING_VER_DP = 0f
    const val WORKSPACE_PADDING_HOR_DP = 0f
    const val CELL_PADDING_TOP_MIN_DP = 7f
    const val CELL_HEIGHT_WIDTH_DIFF_DP = 8f
    const val GRID_CELL_PADDING_DP = 2f
    const val WORKSPACE_TOP_PERCENTAGE = 0.333f

    const val HOTSEAT_HEIGHT_DP = 78f
    const val HOTSEAT_MARGIN_BOTTOM_DP = 26f
    const val HOTSEAT_ICON_PADDING_HOR_DP = 6f
    const val HOTSEAT_ICON_RATIO = 0.8f
    const val HOTSEAT_RADIUS_RATIO = 0.26f
    const val HOTSEAT_BLUR_RADIUS_RATIO = 0.31f
    const val HOTSEAT_STROKE_PX = 2f
    const val HOTSEAT_SMOOTH_WEIGHT = 1.1f

    const val INDICATOR_HEIGHT_DP = 28f
    const val INDICATOR_PADDING_HOR_DP = 14f
    const val INDICATOR_PADDING_VER_DP = 11f
    const val INDICATOR_ITEM_DP = 6f
    const val INDICATOR_SPACING_DP = 6f
    const val INDICATOR_NORMAL_ALPHA = 76
    const val INDICATOR_ACTIVE_ALPHA = 217

    const val ICON_MASK_VIEWPORT = 150f
    const val ICON_MASK_CORNER = 25f
    const val ICON_MASK_CORNER_FRACTION = ICON_MASK_CORNER / ICON_MASK_VIEWPORT

    const val LABEL_SHADOW_RADIUS_DP = 8f
    const val LABEL_SHADOW_COLOR = 0x4D000000
    const val LABEL_COLOR_DARK_WP = -0x1
    const val LABEL_COLOR_BRIGHT_WP = -0x1000000

    const val HOTSEAT_FILL_DARK_WP = 0x4CFFFFFF
    const val HOTSEAT_FILL_BRIGHT_WP = 0x1E000000
    const val HOTSEAT_STROKE_COLOR = 0x2EFFFFFF.toInt()

    /** 单元格内容高度：图标 + 间距 + 文字行高。 */
    fun contentHeightPx(iconPx: Float, drawablePaddingPx: Float, textHeightPx: Float): Float =
        iconPx + drawablePaddingPx + textHeightPx

    /** 单元格宽：可用宽减左右内边距后按列均分。 */
    fun cellWidthPx(widthPx: Float, density: Float): Float {
        val inner = widthPx - 2f * (WORKSPACE_PADDING_HOR_DP + CELL_PADDING_HOR_DP) * density
        return inner / COLUMNS
    }

    /** 单元格高：先按内容下限算，再按手机视口封顶。 */
    fun cellHeightPx(
        widthPx: Float,
        heightPx: Float,
        density: Float,
        textHeightPx: Float,
        hotseatHeightPx: Float,
        hotseatMarginBottomPx: Float,
        indicatorHeightPx: Float,
    ): Float {
        val cellWidth = cellWidthPx(widthPx, density)
        val content = contentHeightPx(
            ICON_SIZE_DP * density,
            ICON_DRAWABLE_PADDING_DP * density,
            textHeightPx,
        )
        val byContent = max(
            2f * CELL_PADDING_TOP_MIN_DP * density + content,
            cellWidth + CELL_HEIGHT_WIDTH_DIFF_DP * density,
        )
        val used = hotseatMarginBottomPx + hotseatHeightPx + indicatorHeightPx +
            2f * CELL_PADDING_VER_DP * density
        val viewport = (heightPx - used) / ROWS
        return min(byContent, max(1f, viewport))
    }

    /** 工作区高度 = 上下内边距 + 行高 × 行数。 */
    fun workspaceHeightPx(cellHeightPx: Float, density: Float): Float =
        2f * CELL_PADDING_VER_DP * density + cellHeightPx * ROWS

    /** 工作区顶部内边距：剩余空间按系统百分比分配。 */
    fun workspacePaddingTopPx(
        heightPx: Float,
        workspaceHeightPx: Float,
        hotseatHeightPx: Float,
        hotseatMarginBottomPx: Float,
        indicatorHeightPx: Float,
        insetTopPx: Float = 0f,
    ): Float {
        val extra = heightPx - workspaceHeightPx - hotseatHeightPx - indicatorHeightPx -
            hotseatMarginBottomPx
        return ((extra - insetTopPx) * WORKSPACE_TOP_PERCENTAGE).roundToInt() + insetTopPx
    }

    /** 图标在单元格内的纵向偏移：剩余空间 × 系数（4 列为 0.5）。 */
    fun iconTopInsetPx(cellHeightPx: Float, contentHeightPx: Float): Float =
        max(0f, (cellHeightPx - contentHeightPx) * 0.5f)

    /** Dock 左右留白：与工作区 + 单元格左内边距一致。 */
    fun hotseatPaddingHorPx(density: Float): Float =
        (WORKSPACE_PADDING_HOR_DP + CELL_PADDING_HOR_DP) * density

    /** Dock 单格宽。 */
    fun hotseatItemWidthPx(widthPx: Float, density: Float): Float =
        (widthPx - 2f * hotseatPaddingHorPx(density)) / HOTSEAT_ICONS

    /** Dock 图标尺寸：不超过桌面图标，且不超过格宽的比例上限。 */
    fun hotseatIconPx(widthPx: Float, density: Float): Float {
        val item = hotseatItemWidthPx(widthPx, density)
        return min(ICON_SIZE_DP * density, HOTSEAT_ICON_RATIO * item)
    }

    /** Dock 背景圆角（普通档）。 */
    fun hotseatRadiusPx(heightPx: Float): Float = heightPx * HOTSEAT_RADIUS_RATIO

    /** Dock 背景圆角（模糊档）。 */
    fun hotseatBlurRadiusPx(heightPx: Float): Float = heightPx * HOTSEAT_BLUR_RADIUS_RATIO

    /** 页点容器宽：左右内边距 + n 个点 + (n-1) 个间距。 */
    fun indicatorWidthPx(pages: Int, density: Float): Float {
        val n = pages.coerceAtLeast(1)
        return 2f * INDICATOR_PADDING_HOR_DP * density +
            n * INDICATOR_ITEM_DP * density + (n - 1) * INDICATOR_SPACING_DP * density
    }

    /** 第 index 个页点左边缘（相对页点容器）。 */
    fun indicatorItemLeftPx(index: Int, density: Float): Float =
        INDICATOR_PADDING_HOR_DP * density +
            index * (INDICATOR_ITEM_DP + INDICATOR_SPACING_DP) * density

    /** 页点在容器内的纵向偏移。 */
    fun indicatorItemTopPx(density: Float): Float =
        (INDICATOR_HEIGHT_DP - INDICATOR_ITEM_DP) * density / 2f

    /** 页点容器底部边距：位于工作区底与 Dock 顶之间的居中位置。 */
    fun indicatorMarginBottomPx(
        hotseatMarginBottomPx: Float,
        workspacePaddingBottomPx: Float,
        hotseatHeightPx: Float,
        density: Float,
    ): Float = (
        hotseatMarginBottomPx + CELL_PADDING_VER_DP * density + workspacePaddingBottomPx +
            hotseatHeightPx - INDICATOR_HEIGHT_DP * density
        ) / 2f

    /** 图标兜底形状的圆角半径：按系统蒙版比例折算，不用固定 dp。 */
    fun iconCornerRadiusPx(iconPx: Float): Float = iconPx * ICON_MASK_CORNER_FRACTION

    /** 文字行高（忽略 font padding）：由调用方传入实测值，这里只做非负钳制。 */
    fun textHeightPx(measured: Float): Float = max(0f, ceil(measured))
}

/** 一屏桌面的求解结果，全部为像素。 */
data class HomeGeometry(
    val widthPx: Float,
    val heightPx: Float,
    val density: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val iconSizePx: Float,
    val drawablePaddingPx: Float,
    val contentHeightPx: Float,
    val workspaceTopPx: Float,
    val workspaceHeightPx: Float,
    val workspacePaddingTopPx: Float,
    val workspacePaddingBottomPx: Float,
    val hotseatLeftPx: Float,
    val hotseatTopPx: Float,
    val hotseatWidthPx: Float,
    val hotseatHeightPx: Float,
    val hotseatRadiusPx: Float,
    val hotseatIconSizePx: Float,
    val hotseatItemWidthPx: Float,
    val indicatorLeftPx: Float,
    val indicatorTopPx: Float,
    val indicatorWidthPx: Float,
    val indicatorHeightPx: Float,
    val indicatorItemSizePx: Float,
    val indicatorSpacingPx: Float,
    val indicatorPaddingHorPx: Float,
    val labelHeightPx: Float,
) {
    /** 第 index 格（行优先）的图标左上角。 */
    fun cellIconLeftPx(index: Int): Float =
        hotseatLeftPx + HomeLayout.GRID_CELL_PADDING_DP * density +
            (index % HomeLayout.COLUMNS) * cellWidthPx +
            (cellWidthPx - 2f * HomeLayout.GRID_CELL_PADDING_DP * density - iconSizePx) / 2f

    fun cellIconTopPx(index: Int): Float =
        workspaceTopPx + (index / HomeLayout.COLUMNS) * cellHeightPx +
            HomeLayout.iconTopInsetPx(cellHeightPx, contentHeightPx)

    /** 第 index 格文字基线相对图标底部的偏移由调用方按字体度量给。 */
    fun cellLabelTopPx(index: Int): Float =
        cellIconTopPx(index) + iconSizePx + drawablePaddingPx

    /** Dock 第 index 个图标左上角。 */
    fun hotseatIconLeftPx(index: Int): Float =
        hotseatLeftPx + index * hotseatItemWidthPx + (hotseatItemWidthPx - hotseatIconSizePx) / 2f

    fun hotseatIconTopPx(): Float = hotseatTopPx + (hotseatHeightPx - hotseatIconSizePx) / 2f

    /** 第 index 个页点左上角。 */
    fun indicatorItemLeftPx(index: Int): Float =
        indicatorLeftPx + indicatorPaddingHorPx +
            index * (indicatorItemSizePx + indicatorSpacingPx)

    fun indicatorItemTopPx(): Float = indicatorTopPx + (indicatorHeightPx - indicatorItemSizePx) / 2f

    companion object {

        /**
         * 按系统顺序求解一屏桌面几何。
         *
         * [labelHeightPx] 传实测文字行高；[insetTopPx] 为状态栏高度，预览可传 0。
         */
        fun solve(
            widthPx: Float,
            heightPx: Float,
            density: Float,
            labelHeightPx: Float,
            pages: Int = 1,
            insetTopPx: Float = 0f,
            blurredHotseat: Boolean = false,
        ): HomeGeometry {
            val d = max(0.01f, density)
            val hotseatH = HomeLayout.HOTSEAT_HEIGHT_DP * d
            val hotseatMargin = HomeLayout.HOTSEAT_MARGIN_BOTTOM_DP * d
            val indicatorH = HomeLayout.INDICATOR_HEIGHT_DP * d
            val icon = HomeLayout.ICON_SIZE_DP * d
            val gap = HomeLayout.ICON_DRAWABLE_PADDING_DP * d
            val label = HomeLayout.textHeightPx(labelHeightPx)
            val content = HomeLayout.contentHeightPx(icon, gap, label)
            val cellW = HomeLayout.cellWidthPx(widthPx, d)
            val cellH = HomeLayout.cellHeightPx(widthPx, heightPx, d, label, hotseatH, hotseatMargin, indicatorH)
            val wsH = HomeLayout.workspaceHeightPx(cellH, d)
            val padTop = HomeLayout.workspacePaddingTopPx(heightPx, wsH, hotseatH, hotseatMargin, indicatorH, insetTopPx)
            val padBottom = max(0f, heightPx - padTop - wsH)
            val hotseatLeft = HomeLayout.hotseatPaddingHorPx(d)
            val hotseatW = widthPx - 2f * hotseatLeft
            val hotseatTop = heightPx - hotseatMargin - hotseatH
            val indicatorMargin = HomeLayout.indicatorMarginBottomPx(hotseatMargin, padBottom, hotseatH, d)
            val indicatorW = HomeLayout.indicatorWidthPx(pages, d)
            return HomeGeometry(
                widthPx = widthPx,
                heightPx = heightPx,
                density = d,
                cellWidthPx = cellW,
                cellHeightPx = cellH,
                iconSizePx = icon,
                drawablePaddingPx = gap,
                contentHeightPx = content,
                workspaceTopPx = padTop,
                workspaceHeightPx = wsH,
                workspacePaddingTopPx = padTop,
                workspacePaddingBottomPx = padBottom,
                hotseatLeftPx = hotseatLeft,
                hotseatTopPx = hotseatTop,
                hotseatWidthPx = hotseatW,
                hotseatHeightPx = hotseatH,
                hotseatRadiusPx = if (blurredHotseat) {
                    HomeLayout.hotseatBlurRadiusPx(hotseatH)
                } else {
                    HomeLayout.hotseatRadiusPx(hotseatH)
                },
                hotseatIconSizePx = HomeLayout.hotseatIconPx(widthPx, d),
                hotseatItemWidthPx = HomeLayout.hotseatItemWidthPx(widthPx, d),
                indicatorLeftPx = (widthPx - indicatorW) / 2f,
                indicatorTopPx = heightPx - indicatorMargin - indicatorH,
                indicatorWidthPx = indicatorW,
                indicatorHeightPx = indicatorH,
                indicatorItemSizePx = HomeLayout.INDICATOR_ITEM_DP * d,
                indicatorSpacingPx = HomeLayout.INDICATOR_SPACING_DP * d,
                indicatorPaddingHorPx = HomeLayout.INDICATOR_PADDING_HOR_DP * d,
                labelHeightPx = label,
            )
        }
    }
}
