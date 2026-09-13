import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
import './admin.css'
import './features/admin/account-management.css'
import './features/security/security.css'
import './features/timeline/timeline.css'

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
