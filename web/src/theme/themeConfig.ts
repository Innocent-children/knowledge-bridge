import type {ThemeConfig} from 'antd'

/**
 * Knowledge Bridge — "Obsidian Console" theme.
 *
 * A refined dark-mode palette built around deep charcoal surfaces,
 * warm amber accents, and crisp DM Sans typography. Designed to feel
 * like a luxury editorial dashboard — information-dense yet elegant.
 */
const themeConfig: ThemeConfig = {
    // Use dark algorithm as the base
    algorithm: undefined, // We handle dark mode manually via token overrides

    token: {
        // ── Brand accent: warm amber / gold ──
        colorPrimary: '#D4A853',
        colorInfo: '#6BA3BE',

        // ── Semantic colors ──
        colorSuccess: '#5EC269',
        colorWarning: '#E8B84B',
        colorError: '#E05C5C',

        // ── Surfaces — deep charcoal layering ──
        colorBgContainer: '#1A1D23',
        colorBgLayout: '#12141A',
        colorBgElevated: '#22262E',
        colorBgSpotlight: '#2A2E38',

        // ── Text ──
        colorText: '#E8E6E1',
        colorTextSecondary: '#9B978F',
        colorTextTertiary: '#6B6860',
        colorTextQuaternary: '#4A473F',

        // ── Borders ──
        colorBorder: '#2E3240',
        colorBorderSecondary: '#252830',
        borderRadius: 10,
        borderRadiusLG: 14,
        borderRadiusSM: 6,

        // ── Typography — DM Sans ──
        fontFamily: '"DM Sans", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        fontSize: 14,
        fontSizeHeading1: 32,
        fontSizeHeading2: 26,
        fontSizeHeading3: 21,
        fontSizeHeading4: 17,
        fontSizeHeading5: 14,
        fontSizeSM: 12,
        fontSizeLG: 16,
        fontWeightStrong: 600,

        lineHeight: 1.65,

        // ── Shadows — subtle warm glow ──
        boxShadow: '0 2px 12px rgba(0, 0, 0, 0.3), 0 0 1px rgba(212, 168, 83, 0.05)',
        boxShadowSecondary: '0 8px 32px rgba(0, 0, 0, 0.4), 0 0 1px rgba(212, 168, 83, 0.08)',

        // ── Motion ──
        motionDurationFast: '0.15s',
        motionDurationMid: '0.25s',
        motionDurationSlow: '0.35s',
    },

    components: {
        Layout: {
            siderBg: '#15171D',
            headerBg: '#1A1D23',
            bodyBg: '#12141A',
        },
        Menu: {
            darkItemBg: 'transparent',
            darkItemSelectedBg: 'rgba(212, 168, 83, 0.12)',
            darkItemHoverBg: 'rgba(255, 255, 255, 0.04)',
            darkItemColor: '#8A8680',
            darkItemSelectedColor: '#D4A853',
            itemMarginInline: 8,
            itemPaddingInline: 16,
            itemBorderRadius: 8,
            itemHeight: 44,
            iconSize: 18,
        },
        Card: {
            colorBgContainer: '#1A1D23',
            borderRadiusLG: 14,
            paddingLG: 24,
        },
        Table: {
            colorBgContainer: '#1A1D23',
            headerBg: '#1E2128',
            headerColor: '#9B978F',
            rowHoverBg: 'rgba(212, 168, 83, 0.04)',
            borderColor: '#252830',
            headerBorderRadius: 10,
            cellPaddingBlock: 14,
            cellPaddingInline: 16,
        },
        Button: {
            borderRadius: 8,
            controlHeight: 38,
            fontWeight: 500,
            primaryShadow: '0 2px 8px rgba(212, 168, 83, 0.25)',
        },
        Input: {
            colorBgContainer: '#1E2128',
            colorBorder: '#2E3240',
            activeBorderColor: '#D4A853',
            hoverBorderColor: 'rgba(212, 168, 83, 0.4)',
            activeShadow: '0 0 0 2px rgba(212, 168, 83, 0.12)',
            borderRadius: 8,
        },
        Select: {
            colorBgContainer: '#1E2128',
            colorBorder: '#2E3240',
            optionSelectedBg: 'rgba(212, 168, 83, 0.12)',
            optionActiveBg: 'rgba(255, 255, 255, 0.04)',
            borderRadius: 8,
        },
        Modal: {
            contentBg: '#1E2128',
            headerBg: '#1E2128',
            titleColor: '#E8E6E1',
            borderRadiusLG: 16,
        },
        Drawer: {
            colorBgElevated: '#1E2128',
        },
        Tag: {
            borderRadiusSM: 6,
        },
        Tabs: {
            inkBarColor: '#D4A853',
            itemActiveColor: '#D4A853',
            itemSelectedColor: '#D4A853',
            itemHoverColor: '#E8E6E1',
            itemColor: '#8A8680',
        },
        Collapse: {
            colorBgContainer: '#1E2128',
            headerBg: '#22262E',
            contentBg: '#1A1D23',
            borderRadiusLG: 10,
        },
        Descriptions: {
            colorBgContainer: '#1A1D23',
            labelBg: '#1E2128',
            titleColor: '#E8E6E1',
            contentColor: '#C8C4BC',
            colorSplit: '#252830',
        },
        Alert: {
            colorInfoBg: 'rgba(107, 163, 190, 0.08)',
            colorInfoBorder: 'rgba(107, 163, 190, 0.2)',
            colorErrorBg: 'rgba(224, 92, 92, 0.08)',
            colorErrorBorder: 'rgba(224, 92, 92, 0.2)',
            colorWarningBg: 'rgba(232, 184, 75, 0.08)',
            colorWarningBorder: 'rgba(232, 184, 75, 0.2)',
            colorSuccessBg: 'rgba(94, 194, 105, 0.08)',
            colorSuccessBorder: 'rgba(94, 194, 105, 0.2)',
            borderRadiusLG: 10,
        },
        Notification: {
            colorBgElevated: '#22262E',
        },
        Segmented: {
            itemSelectedBg: '#D4A853',
            itemSelectedColor: '#12141A',
            trackBg: '#1E2128',
            itemColor: '#8A8680',
            itemHoverColor: '#E8E6E1',
            borderRadiusSM: 8,
        },
        Switch: {
            colorPrimary: '#D4A853',
            colorPrimaryHover: '#E0B96A',
        },
        Pagination: {
            colorBgContainer: '#1E2128',
            colorBgTextHover: 'rgba(212, 168, 83, 0.08)',
            itemActiveBg: 'rgba(212, 168, 83, 0.15)',
            colorPrimary: '#D4A853',
        },
        Spin: {
            colorPrimary: '#D4A853',
        },
        Empty: {
            colorTextDisabled: '#4A473F',
        },
        Statistic: {
            contentFontSize: 28,
        },
    },
}

export default themeConfig
