# Meal Mapper 2.0: test

About 30 minutes. Install over the old app (keys, settings, history stay). Everything happens in the chat.

## A. Set-up (3 min)
1. Settings: Gemini key and Deepgram key show "saved". Test each. Your kitchen filled in.
2. The top of the chat shows kcal left and P · C · F · Fibre · Sugar, matching Google Health.

## B. Talk, don't fill forms (10 min)
3. Type or say: "Breakfast: 2 egg omelette, 2 toast and a bowl of papaya". Send.
   PASS: one card with 3 items, each in a sensible unit (egg, slice, bowl), kcal, and a source line
   (Databank / Online / AI estimate). Nothing is logged yet.
4. Say: "make it 3 toast and remove the papaya". PASS: the same card updates (not a new card).
5. Tap − / + and the unit menu on one item. PASS: grams and kcal update together.
6. Tap Log it. PASS: "Logged ✓ Breakfast"; the ticker goes up; Google Health shows the items.
7. Tap Save as meal, keep "My usual breakfast".

## C. Memory and misspellings (5 min)
8. Say "1 scoop True Basics whey in water, pre breakfast". Log it.
9. Tomorrow (or now), say "tru basic way protein 2 scoop". PASS: the card shows True Basics Whey, 2 scoop,
   source "Your food", and it appears instantly (no web search).
10. Say just "breakfast" or "my usual breakfast". PASS: the card proposes omelette, toast (and papaya if saved),
    with a question; one tap logs it. The chip "My usual breakfast" above the chat box does the same.
11. After 2 logs of the same food at the same meal, it appears as a chip above the chat box.

## D. Places, labels, barcodes (8 min)
12. "Lunch at Swati Snacks: panki and sugarcane juice". PASS: source "Online · Restaurant values…" or
    "AI estimate" with a note; never a made-up source.
13. + → Take a photo of a nutrition label, send with "4 biscuits". PASS: source "Label · your photo".
14. + → Scan a barcode. PASS: the product lands in the chat as a card with serving / pack / g units.
15. A menu photo with "what should I order?". PASS: a short answer with eggetarian picks, no card.

## E. Days and fixes (4 min)
16. "Yesterday dinner: 2 phulka, bhindi, 1 katori dal". PASS: card shows Yesterday · Dinner; Google Health
    adds it to yesterday.
17. Undo on a logged card removes it from Google Health.
18. Settings → Your foods and meals: delete a wrong food. PASS: it is no longer recognised from memory.
