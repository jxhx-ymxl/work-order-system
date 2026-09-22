import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'
import 'element-plus/dist/index.css'
import './style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// Element Plus 中文国际化：分页器/弹窗/日期等组件文案显示中文
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
