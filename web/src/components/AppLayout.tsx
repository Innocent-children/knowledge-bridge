import {type CSSProperties, type FC, useEffect, useState} from 'react'
import {Outlet, useLocation, useNavigate} from 'react-router-dom'
import {Drawer, Grid, Layout, Menu, Tooltip} from 'antd'
import {
    AuditOutlined,
    CloudUploadOutlined,
    DashboardOutlined,
    FileTextOutlined,
    MenuFoldOutlined,
    MenuUnfoldOutlined,
    MessageOutlined,
} from '@ant-design/icons'

const {Sider, Content} = Layout
const {useBreakpoint} = Grid

const navItems = [
    {key: '/dashboard', icon: <DashboardOutlined/>, label: '仪表盘'},
    {key: '/chat', icon: <MessageOutlined/>, label: '对话'},
    {key: '/ingest', icon: <CloudUploadOutlined/>, label: '入库'},
    {key: '/review', icon: <AuditOutlined/>, label: '审核'},
    {key: '/documents', icon: <FileTextOutlined/>, label: '文档'},
]

/* ── Sidebar logo / brand mark ─────────────────────────── */
const BrandMark: FC<{ collapsed: boolean }> = ({collapsed}) => (
    <div
        style={{
            height: 72,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: collapsed ? '0 8px' : '0 20px',
            transition: 'all 0.3s cubic-bezier(0.22, 1, 0.36, 1)',
            position: 'relative',
        }}
    >
        {/* Amber accent dot */}
        <div
            style={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                background: '#D4A853',
                boxShadow: '0 0 12px rgba(212, 168, 83, 0.5)',
                marginRight: collapsed ? 0 : 12,
                flexShrink: 0,
                transition: 'margin 0.3s cubic-bezier(0.22, 1, 0.36, 1)',
            }}
        />
        {!collapsed && (
            <span
                style={{
                    fontFamily: '"Playfair Display", serif',
                    fontSize: 17,
                    fontWeight: 700,
                    color: '#E8E6E1',
                    letterSpacing: '-0.02em',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    opacity: collapsed ? 0 : 1,
                    transition: 'opacity 0.2s ease',
                }}
            >
        Knowledge Bridge
      </span>
        )}
    </div>
)

/* ── Sidebar bottom section ────────────────────────────── */
const SidebarFooter: FC<{ collapsed: boolean }> = ({collapsed}) => (
    <div
        style={{
            padding: collapsed ? '16px 8px' : '16px 20px',
            borderTop: '1px solid rgba(255, 255, 255, 0.04)',
            transition: 'padding 0.3s cubic-bezier(0.22, 1, 0.36, 1)',
        }}
    >
        <div
            style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px 12px',
                borderRadius: 8,
                background: 'rgba(212, 168, 83, 0.06)',
                border: '1px solid rgba(212, 168, 83, 0.08)',
            }}
        >
            <div
                style={{
                    width: 28,
                    height: 28,
                    borderRadius: 7,
                    background: 'linear-gradient(135deg, #D4A853 0%, #B8923E 100%)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 12,
                    fontWeight: 700,
                    color: '#12141A',
                    flexShrink: 0,
                }}
            >
                KB
            </div>
            {!collapsed && (
                <div style={{overflow: 'hidden'}}>
                    <div style={{fontSize: 12, fontWeight: 600, color: '#E8E6E1', lineHeight: 1.3}}>
                        Console
                    </div>
                    <div style={{fontSize: 11, color: '#6B6860', lineHeight: 1.3}}>
                        v1.0.0
                    </div>
                </div>
            )}
        </div>
    </div>
)

const AppLayout: FC = () => {
    const location = useLocation()
    const navigate = useNavigate()
    const screens = useBreakpoint()

    const isMobile = !screens.md
    const [drawerOpen, setDrawerOpen] = useState(false)
    const [collapsed, setCollapsed] = useState(false)

    useEffect(() => {
        if (!isMobile) setDrawerOpen(false)
    }, [isMobile])

    const selectedKey = '/' + location.pathname.split('/')[1]

    const handleMenuClick = (info: { key: string }) => {
        navigate(info.key)
        if (isMobile) setDrawerOpen(false)
    }

    const siderWidth = collapsed ? 72 : 240

    const sidebarStyle: CSSProperties = {
        overflow: 'hidden',
        height: '100vh',
        position: 'fixed',
        left: 0,
        top: 0,
        bottom: 0,
        zIndex: 100,
        display: 'flex',
        flexDirection: 'column',
        background: '#15171D',
        borderRight: '1px solid rgba(255, 255, 255, 0.04)',
    }

    const menuContent = (
        <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            items={navItems}
            onClick={handleMenuClick}
            style={{
                borderInlineEnd: 'none',
                background: 'transparent',
                padding: '8px 6px',
            }}
        />
    )

    const sidebarInner = (
        <>
            <BrandMark collapsed={collapsed && !isMobile}/>
            <div className="accent-line" style={{margin: '0 16px'}}/>
            <div style={{flex: 1, overflowY: 'auto', overflowX: 'hidden', marginTop: 8}}>
                {menuContent}
            </div>
            <SidebarFooter collapsed={collapsed && !isMobile}/>
        </>
    )

    return (
        <Layout style={{minHeight: '100vh', background: '#12141A'}} className="noise-bg">
            {/* Desktop sidebar */}
            {!isMobile && (
                <Sider
                    collapsible
                    collapsed={collapsed}
                    onCollapse={setCollapsed}
                    width={240}
                    collapsedWidth={72}
                    trigger={null}
                    style={sidebarStyle}
                >
                    {sidebarInner}
                </Sider>
            )}

            {/* Mobile drawer */}
            {isMobile && (
                <Drawer
                    placement="left"
                    open={drawerOpen}
                    onClose={() => setDrawerOpen(false)}
                    width={260}
                    styles={{
                        body: {
                            padding: 0,
                            background: '#15171D',
                            display: 'flex',
                            flexDirection: 'column'
                        },
                        header: {display: 'none'},
                        wrapper: {boxShadow: '4px 0 24px rgba(0, 0, 0, 0.5)'},
                    }}
                >
                    {sidebarInner}
                </Drawer>
            )}

            <Layout
                style={{
                    marginLeft: isMobile ? 0 : siderWidth,
                    transition: 'margin-left 0.3s cubic-bezier(0.22, 1, 0.36, 1)',
                    background: '#12141A',
                    minHeight: '100vh',
                }}
            >
                {/* Top bar */}
                <div
                    style={{
                        height: 56,
                        display: 'flex',
                        alignItems: 'center',
                        padding: '0 24px',
                        background: 'rgba(18, 20, 26, 0.8)',
                        backdropFilter: 'blur(12px)',
                        WebkitBackdropFilter: 'blur(12px)',
                        borderBottom: '1px solid rgba(255, 255, 255, 0.04)',
                        position: 'sticky',
                        top: 0,
                        zIndex: 99,
                        gap: 12,
                    }}
                >
                    {isMobile ? (
                        <span
                            onClick={() => setDrawerOpen(true)}
                            style={{
                                fontSize: 18,
                                cursor: 'pointer',
                                color: '#9B978F',
                                display: 'flex',
                                alignItems: 'center',
                                padding: 4,
                            }}
                            role="button"
                            aria-label="打开导航菜单"
                            tabIndex={0}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') setDrawerOpen(true)
                            }}
                        >
              <MenuUnfoldOutlined/>
            </span>
                    ) : (
                        <Tooltip title={collapsed ? '展开侧栏' : '收起侧栏'}>
              <span
                  onClick={() => setCollapsed(!collapsed)}
                  style={{
                      fontSize: 16,
                      cursor: 'pointer',
                      color: '#6B6860',
                      display: 'flex',
                      alignItems: 'center',
                      padding: 4,
                      borderRadius: 6,
                      transition: 'color 0.2s ease',
                  }}
                  role="button"
                  aria-label={collapsed ? '展开侧栏' : '收起侧栏'}
                  tabIndex={0}
                  onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') setCollapsed(!collapsed)
                  }}
                  onMouseEnter={(e) => (e.currentTarget.style.color = '#D4A853')}
                  onMouseLeave={(e) => (e.currentTarget.style.color = '#6B6860')}
              >
                {collapsed ? <MenuUnfoldOutlined/> : <MenuFoldOutlined/>}
              </span>
                        </Tooltip>
                    )}

                    {isMobile && (
                        <span
                            style={{
                                fontFamily: '"Playfair Display", serif',
                                fontSize: 15,
                                fontWeight: 700,
                                color: '#E8E6E1',
                                letterSpacing: '-0.02em',
                            }}
                        >
              Knowledge Bridge
            </span>
                    )}

                    {/* Breadcrumb-style current page indicator */}
                    {!isMobile && (
                        <span style={{fontSize: 13, color: '#6B6860', fontWeight: 500}}>
              {navItems.find((n) => n.key === selectedKey)?.label ?? ''}
            </span>
                    )}
                </div>

                <Content
                    style={{
                        margin: 0,
                        minHeight: 280,
                        background: '#12141A',
                    }}
                >
                    <div className="page-enter">
                        <Outlet/>
                    </div>
                </Content>
            </Layout>
        </Layout>
    )
}

export default AppLayout
