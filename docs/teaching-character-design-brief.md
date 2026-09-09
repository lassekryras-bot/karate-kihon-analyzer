# Teaching Character Design Brief

## Purpose

Create two recurring teaching characters for the Karate app:

- **The Boy** — the learner stand-in.
- **The Sensei** — the calm guide.

These characters should help explain activities, choices, corrections, motivation, and emotions in a way that is easy for children to understand while still looking appropriate for teenagers and adults.

The target visual principle is:

> **Simple enough for children, mature enough for everyone.**

The app should not feel like a children's cartoon app. Simplicity should come from clear image language, posture, and emotion.

## Overall visual direction

- Clean, modern, friendly illustration style.
- Karate-related and immediately readable on a phone.
- Clear silhouettes and simple poses.
- White karate gi.
- Warm off-white or transparent background depending on asset use.
- App red used as an accent, not as the belt color.
- Limited detail so assets can later be converted to reusable transparent SVGs.
- Expressive enough for a child to understand without text.
- Suitable for roughly age 7 through adults.

Avoid:

- chibi or oversized-head proportions;
- baby-like expressions;
- excessive anime styling;
- aggressive or frightening poses;
- unnecessary decorative detail;
- text baked into reusable illustration assets.

---

# The Boy

## Role

The boy is the learner character. He is a normal beginner, not a perfect karate student.

He represents:

- curiosity;
- effort;
- beginner mistakes;
- distraction;
- tiredness;
- doubt;
- recovery;
- trying again.

The boy should be someone learners can recognize themselves in.

## Core character idea: two sides

The boy has two equally important sides.

### Positive side

- listening;
- ready;
- focused;
- brave;
- trying;
- determined;
- recovering after a mistake.

### Negative side

- tired;
- distracted;
- confused;
- doubtful;
- thinking about pizza;
- wanting to sleep;
- wanting to go home;
- feeling that training is hard.

The negative side is never portrayed as naughty or bad. It represents normal thoughts and feelings that happen during practice.

The teaching pattern is often:

> distraction or difficulty → refocus → try again

## Appearance

- Child / young beginner.
- Short, slightly messy dark hair.
- Friendly face.
- Simple expressive eyes.
- White karate gi.
- **Orange belt in all recurring beginner-character illustrations.**
- Youthful but believable proportions.
- Must work in front, 3/4, and side views.

## Reusable states

### Listening

Meaning:

- “I heard you.”
- “Got it.”

Visual language:

- attentive posture;
- looking toward the Sensei;
- alert but calm expression;
- optional small attention marks.

### Ready

Meaning:

- “I’m ready.”
- “Let’s go.”

Visual language:

- upright body;
- feet planted;
- guard up;
- hands / forearms protecting the head;
- focused expression.

### Trying / Keep going

Meaning:

- “I’ll keep trying.”
- “I’ll do my best.”

Visual language:

- active stance, punch, kick, or reset;
- determined expression;
- some sweat is acceptable;
- effort is visible but positive.

### Tired

Meaning:

- training is hard;
- the learner is struggling.

Visual language:

- bent posture;
- hands on knees or slumped body;
- sweat drops;
- tired face.

### Distracted

Meaning:

- the mind is somewhere else.

Visual language:

- thought bubble;
- pizza, bed/sleep, house/home, or another simple everyday distraction;
- dreamy or low-energy expression.

### Confused

Meaning:

- “I don’t understand.”
- a simple beginner question.

Visual language:

- tilted head;
- uncertain expression;
- small questioning gesture.

### Recovery

Meaning:

- “Okay, I get it.”
- “I’ll try again.”

Visual language:

- posture resets;
- eyes refocus;
- ready stance returns;
- determined but not exaggerated.

---

# The Sensei

## Role

The Sensei is the calm guide and visual anchor.

He represents:

- structure;
- safety;
- clear instruction;
- encouragement;
- consistency.

He should feel like **safe authority**.

## Personality

- calm;
- kind;
- patient;
- warm;
- confident;
- concise;
- supportive.

Avoid angry, threatening, or overly comic expressions.

## Appearance

- Adult male karate teacher.
- Short dark hair.
- White gi.
- Black / dark belt.
- Calm, readable face.
- Relaxed but confident posture.

## Reusable states

### Speaking

- Gives a short instruction.
- Often paired with a separate blank speech bubble for localization.

### Demonstrating

- Shows a stance or movement clearly.

### Encouraging

- Mildly energetic support.
- Useful when the boy is tired or struggling.

### Neutral / Calm

- Default guide state for introductions and simple explanations.

---

# Relationship between the characters

The two-character teaching pattern is:

1. Sensei gives a short direction or cue.
2. Boy reacts.
3. The reaction may be focused, confused, distracted, or tired.
4. The learner sees a clear visual contrast.
5. Boy returns to focus or tries again.

Simple conceptual summary:

- **Sensei = external guide**
- **Boy = learner’s inner experience**

This relationship should remain consistent across activities.

---

# Simple visual language for Osu

Do not teach difficult abstract vocabulary to beginner users when a feeling or action can communicate the idea.

Preferred beginner concepts:

- **I heard you**
- **I’m ready**
- **I’ll keep trying**

Useful humorous negative contrasts:

- thinking about pizza;
- wanting to sleep;
- wanting to go home.

These work because they represent realistic thoughts a child might have during training and make the positive Osu response easier to understand visually.

---

# Asset strategy

Design the illustrations as reusable pieces, not full baked-in quiz screenshots.

## Sensei assets

- `sensei_speaking`
- `sensei_neutral`
- `sensei_demonstrating`
- `sensei_encouraging`

## Boy positive assets

- `boy_listening`
- `boy_ready_guard`
- `boy_trying`
- `boy_punching`
- `boy_recovering`

## Boy negative assets

- `boy_tired`
- `boy_confused`
- `boy_distracted_pizza`
- `boy_sleepy`
- `boy_wants_home`

## Supporting assets

- `speech_bubble_blank`
- `thought_pizza`
- `thought_sleep`
- `thought_home`
- `answer_correct_badge`
- `answer_wrong_badge`

Prefer transparent assets suitable for later SVG use. Keep speech and instructional text outside the illustration whenever possible so localization happens in the app.

---

# Design rules

## Do

- make feelings obvious through posture;
- use very simple visual situations;
- let image language carry most of the meaning;
- keep wrong answers relatable and lightly funny;
- show that tiredness or distraction can be followed by renewed focus;
- preserve the same boy and Sensei appearance across the app;
- keep the boy’s belt orange unless a deliberate future design decision changes the character.

## Do not

- rely on difficult words such as “acknowledgement” or “perseverance” in beginner visuals;
- make the boy look bad, lazy, or shameful;
- humiliate failure;
- make the Sensei intimidating;
- overcomplicate the scenario;
- bake translated text into reusable character artwork.

---

# Short reusable image-generation brief

> Create the recurring Karate app learner boy and Sensei. The boy is a beginner with short dark messy hair, a white gi, and an orange belt. He is expressive and relatable, with a positive side that is listening, ready, focused, and willing to try, and a negative side that is tired, distracted, confused, or thinking about pizza, sleep, or going home. He can struggle and recover. The Sensei is a calm adult guide with short dark hair, a white gi, and a black belt. He is patient, warm, clear, and supportive. Use a simple, friendly karate illustration style that children can understand but that remains appropriate for teens and adults. Prefer strong body language, minimal text, reusable transparent assets, and localization-safe blank speech bubbles.
