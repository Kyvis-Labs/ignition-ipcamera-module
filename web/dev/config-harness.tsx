// Renders the gateway config page standalone; serve.py proxies its /data calls to the gateway.
import * as React from "react";
import { createRoot } from "react-dom/client";
import { CameraConfigPage } from "../src/config/CameraConfigPage";

createRoot(document.getElementById("root")!).render(<CameraConfigPage />);
