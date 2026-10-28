import "./api";
import { createApp } from "vue";
import App from "./App.vue";
import { partnerRouter } from "./router";
import "./styles.css";

createApp(App).use(partnerRouter).mount("#app");
