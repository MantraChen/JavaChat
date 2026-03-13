# Chat Client (Vue 3 + Vite)

Frontend for the Chat server: Vue 3, Pinia, and Vite. Build output is written into the backend’s static folder so the Java server serves the app at `/`.

**Part of a personal learning project.** Feel free to take and adapt this code.

---

## Setup

```bash
npm install
```

---

## Scripts

| Command        | Description |
|----------------|-------------|
| `npm run dev`  | Start dev server at `http://localhost:5173`. Proxies `/api`, `/ws`, and `/files` to the backend (default port 9091). |
| `npm run build`| Production build. Output goes to `../chat-server/src/main/resources/static/`. Does not clear that directory (e.g. `register.html`, `admin.html` are kept). |
| `npm run preview` | Preview the production build locally. |

---

## Structure

- `src/main.js` — App entry; Pinia + global styles.
- `src/App.vue` — Root: login screen or chat layout.
- `src/components/` — LoginCard, Sidebar, ChatWindow, MessageBubble, ChatInput.
- `src/stores/` — `auth.js` (token, user, theme), `chat.js` (WebSocket, messages, sessions, sync).
- `src/api.js` — HTTP helpers (login, users, online); WS URL.
- `src/styles/` — `variables.css` (theme tokens), `base.css` (layout and chat UI).

---

## License

MIT.
