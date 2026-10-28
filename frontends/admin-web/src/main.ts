import "./api";
import { createApp } from "vue";
import App from "./App.vue";
import { adminRouter } from "./router";
import "./styles.css";
import "./loading-states.css";
import { createAdminAuth } from "./adminAuth";

createApp(App, { session: createAdminAuth(adminRouter) }).use(adminRouter).mount("#app");
