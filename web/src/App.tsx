import type {FC} from 'react'
import {BrowserRouter, Navigate, Route, Routes} from 'react-router-dom'
import {ConfigProvider} from 'antd'
import themeConfig from './theme/themeConfig'
import AppLayout from './components/AppLayout'
import DashboardPage from './pages/DashboardPage'
import ChatPage from './pages/ChatPage'
import IngestPage from './pages/IngestPage'
import ReviewPage from './pages/ReviewPage'
import DocumentsPage from './pages/DocumentsPage'

/**
 * Root application component.
 * Sets up Ant Design theming, React Router, and the application shell.
 */
const App: FC = () => {
    return (
        <ConfigProvider theme={themeConfig}>
            <BrowserRouter>
                <Routes>
                    <Route element={<AppLayout/>}>
                        <Route path="/dashboard" element={<DashboardPage/>}/>
                        <Route path="/chat" element={<ChatPage/>}/>
                        <Route path="/ingest" element={<IngestPage/>}/>
                        <Route path="/review" element={<ReviewPage/>}/>
                        <Route path="/documents" element={<DocumentsPage/>}/>
                        <Route path="/" element={<Navigate to="/dashboard" replace/>}/>
                        <Route path="*" element={<Navigate to="/dashboard" replace/>}/>
                    </Route>
                </Routes>
            </BrowserRouter>
        </ConfigProvider>
    )
}

export default App
