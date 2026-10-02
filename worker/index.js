const MODEL = "gemini-2.5-flash";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Zivaya server चालू है।", {
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }
    if (!env.GEMINI_API_KEY) {
      return json({ error: "key missing" }, 500);
    }

    let body;
    try {
      body = await request.json();
    } catch (e) {
      return json({ error: "bad request" }, 400);
    }

    const lang = body.language === "en" ? "English" : "सरल हिंदी";
    const msgs = Array.isArray(body.messages) ? body.messages.slice(-10) : [];
    const contents = msgs
      .filter((m) => m && typeof m.content === "string" && m.content.trim())
      .map((m) => ({
        role: m.role === "assistant" ? "model" : "user",
        parts: [{ text: m.content.slice(0, 2000) }],
      }));
    if (contents.length === 0) {
      return json({ error: "empty" }, 400);
    }

    const system =
      "तुम ज़िवाया हो, एक दोस्ताना महिला AI सहायक। " +
      "जवाब " + lang + " में दो-तीन छोटे वाक्यों में दो, क्योंकि जवाब बोलकर सुनाया जाएगा। " +
      "तारे (*), # जैसे निशान मत लिखो। " +
      "तुम फ़ोन के ऐप या संदेश अपने-आप नहीं चला सकती, यह झूठ मत बोलना। " +
      "पता न हो तो साफ़ कह दो कि पता नहीं।";

    const url =
      "https://generativelanguage.googleapis.com/v1beta/models/" +
      MODEL +
      ":generateContent";

    const r = await fetch(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-goog-api-key": env.GEMINI_API_KEY,
      },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: system }] },
        contents: contents,
        generationConfig: {
          maxOutputTokens: 800,
          temperature: 0.7,
          thinkingConfig: { thinkingBudget: 0 },
        },
      }),
    });

    if (!r.ok) {
      return json({ error: "gemini error", status: r.status }, 502);
    }
    const data = await r.json();
    const parts = (data.candidates && data.candidates[0] &&
      data.candidates[0].content && data.candidates[0].content.parts) || [];
    const reply = parts.map((p) => p.text || "").join("").trim();
    return json({ reply: reply || "माफ़ कीजिए, मुझे जवाब नहीं मिला।" });
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status: status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
