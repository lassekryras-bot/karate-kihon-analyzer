# Teaching Characters Agent Guide

## Purpose

Use the recurring **Boy** and **Sensei** characters consistently across learning activities in the Karate app.

These characters are part of the teaching system, not decorative artwork.

- **Boy** = learner stand-in and inner experience.
- **Sensei** = calm external guide.

The goal is to make learning understandable through simple image language, especially for younger users, while keeping the app appropriate for teenagers and adults.

---

# Core behavior

When authoring a learning activity, prefer this pattern:

1. Sensei gives a short instruction, cue, or question.
2. Boy reacts.
3. The boy may be focused, confused, tired, or distracted.
4. The learner chooses or sees the better response.
5. The boy returns to focus and continues.

Use simple feelings and actions before abstract explanation.

---

# The Boy

## Character role

The Boy represents the learner.

He may be:

- listening;
- ready;
- trying;
- curious;
- confused;
- tired;
- distracted;
- discouraged;
- successful after retrying.

He is allowed to make mistakes and have normal negative thoughts.

Examples:

- thinking about pizza;
- wanting to sleep;
- wanting to go home;
- feeling tired;
- not understanding immediately.

These states should never be framed as bad behavior. They are relatable learner states.

## Positive states

Use for correct responses and demonstrations:

- `LISTENING`
- `READY`
- `GUARD_UP`
- `TRYING`
- `PUNCHING`
- `RECOVERING`

## Negative states

Use for contrast, wrong answers, and internal thoughts:

- `TIRED`
- `CONFUSED`
- `DISTRACTED_PIZZA`
- `SLEEPY`
- `WANTS_HOME`

## Visual constants

Unless a deliberate future design change says otherwise:

- short dark slightly messy hair;
- white gi;
- **orange belt**;
- same face and core proportions across the app.

Do not switch belt color between screens just for variety.

---

# The Sensei

## Character role

The Sensei represents:

- instruction;
- structure;
- safety;
- encouragement;
- calm authority.

He should be:

- patient;
- warm;
- concise;
- supportive;
- visually stable.

## Recommended states

- `SPEAKING`
- `NEUTRAL`
- `DEMONSTRATING`
- `ENCOURAGING`

## Visual constants

- adult male;
- short dark hair;
- white gi;
- black / dark belt;
- calm expression;
- never threatening or angry by default.

---

# Teaching principle: simple picture-language

For beginner-facing lessons, do not rely on abstract vocabulary when a simple feeling or action can communicate the idea.

Prefer:

- “I heard you.”
- “I’m ready.”
- “I’ll keep trying.”

Avoid requiring children to understand terms such as:

- acknowledgement;
- perseverance;
- respect;
- commitment;

These ideas may be taught later in deeper content, but beginner activities should first make the feeling understandable.

---

# Osu lesson guidance

The current beginner visual model for Osu is:

## Positive meanings

### I heard you

Use:

- Sensei speaking;
- Boy paying attention;
- simple alert posture.

### I’m ready

Use:

- Boy upright;
- guard up;
- forearms / hands protecting the head;
- feet planted;
- focused expression.

### I’ll keep trying

Use:

- Boy visibly tired or sweaty;
- then choosing to continue;
- a simple punch, kick, guard reset, or ready pose.

## Negative contrast ideas

Use normal thoughts that can pull attention away from training:

- pizza;
- sleep;
- going home.

These are intentionally simple, funny, and recognizable.

Do not portray the negative state as naughty. The teaching idea is:

> difficult thought → refocus → continue

---

# Quiz pattern

A preferred image-first quiz composition is:

## Top half

- Sensei and Boy in the situation.
- Sensei has a **blank speech bubble**.
- The app adds translated text at runtime.

## Bottom half

Two large image answer cards:

- one correct visual state;
- one wrong / distracted visual state.

Keep answer cards image-first and easily tappable.

Examples:

### Ready

- correct: Boy with guard up, focused and ready;
- wrong: Boy yawning and thinking about bed.

### Keep trying

- correct: tired Boy resetting guard / continuing;
- wrong: tired Boy thinking about going home.

### Listening

- correct: attentive Boy;
- wrong: Boy thinking about pizza.

---

# Localization rules

Reusable character assets should not contain translated instructional text.

Prefer:

- blank speech bubble asset;
- separate thought-bubble illustrations;
- app-rendered strings placed over / near the image.

This allows the same visual asset to work across languages.

Do not bake English text into reusable SVG/PNG character assets unless the text itself is intentionally part of the artwork.

---

# UX rules for failure

When the user or Boy is wrong:

- keep the tone warm;
- allow humor;
- avoid shame;
- make retrying easy;
- show that failure is recoverable.

The intended feeling is:

> “That could be me. I can try again.”

Never use the Boy to mock the learner.

---

# Asset reuse

Treat the characters as a visual component library rather than one-off pictures.

Suggested logical identifiers:

```text
character/boy/listening
character/boy/ready_guard
character/boy/trying
character/boy/punching
character/boy/recovering
character/boy/tired
character/boy/confused
character/boy/distracted_pizza
character/boy/sleepy
character/boy/wants_home

character/sensei/speaking
character/sensei/neutral
character/sensei/demonstrating
character/sensei/encouraging

bubble/speech_blank
bubble/thought_pizza
bubble/thought_sleep
bubble/thought_home

answer/correct_badge
answer/wrong_badge
```

Prefer transparent SVG for final reusable vector assets where practical.

---

# Image-generation instructions

When generating a new character image, always specify:

1. which recurring character is used;
2. Boy emotional / physical state;
3. whether the scene is correct or wrong-answer imagery;
4. orange belt for the Boy;
5. black belt for the Sensei;
6. whether the speech bubble must remain blank;
7. localization-safe composition;
8. simple app-friendly karate illustration style;
9. suitable for children, teens, and adults;
10. no unnecessary background if the asset will later become SVG.

Useful wording:

> Use the same recurring Karate app Boy: short dark messy hair, white gi, orange belt, friendly expressive face. Keep the same recurring Sensei: adult male, short dark hair, white gi, black belt, calm and supportive. Use clear body language and simple image language. Keep the illustration friendly but not babyish, and suitable for reuse in a mobile learning app.

---

# When to use the characters

Good uses:

- onboarding;
- Learn activities;
- quizzes;
- voice-interaction lessons;
- simple demonstrations;
- motivational moments;
- correction / retry flows.

Usually avoid them in:

- technical analysis reports;
- measurement-heavy screens;
- advanced biomechanics explanations;
- dense graphs and diagnostic tools.

---

# Authoring checklist

Before adding a character scene, verify:

- [ ] Is the Boy still visually the same recurring Boy?
- [ ] Is his belt orange?
- [ ] Is the Sensei calm and supportive?
- [ ] Can the main feeling be understood without reading much text?
- [ ] Is the scenario simple enough for a child?
- [ ] Is the wrong answer relatable rather than shameful?
- [ ] Is there a clear route back to focus / trying again?
- [ ] Is translated text kept outside reusable illustration assets?
- [ ] Can the asset be reused later as a transparent SVG or component?
- [ ] Does the result still look acceptable for teenage and adult users?

---

# Short Copilot instruction

> Reuse the recurring Boy and Sensei characters consistently throughout learning activities. The Boy is the learner stand-in and can show both positive and difficult states: listening, ready, trying, tired, distracted, confused, and recovering. He always uses the established white gi and orange belt unless explicitly changed by design. The Sensei is the calm guide in a white gi and black belt, patient, warm, and visually stable. Teach difficult concepts through simple feelings and image contrast rather than abstract words. Prefer relatable wrong states such as thinking about pizza, sleep, or going home, followed by refocus and continued effort. Keep reusable artwork localization-safe with blank speech bubbles and minimal baked-in text.
