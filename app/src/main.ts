import { mount } from "svelte";
import "./app.css";
import App from "./App.svelte";

const theme = (() => {
  try {
    return localStorage.getItem("rocketmaxxing.theme");
  } catch {
    return null;
  }
})();
if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;

export default mount(App, { target: document.getElementById("app")! });
