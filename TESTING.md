# Meal Mapper: full test (version 1.3.0)

About 60 minutes, in one sitting, plus the two-week meal check in Part H. Do the parts in order.
Write down each FAIL with a screenshot. Pass = every step marked PASS, or the fail is explained in the notes.

Before you start: install the new APK over the old one (your settings, key and history stay).

## A. Set-up (5 min)
1. Settings > You: age, weight, cap are still there. PASS if kept.
2. Settings > Your kitchen: katori size (fill your katori with water, pour into a measuring cup), oil + ghee
   bought per month in litres, people eating at home. Save. PASS if the screen shows "≈ N g oil and ghee per person per day".
3. Settings > Gemini > Test key. PASS if "Ready … Google Search works".
4. Home. PASS if the ticker matches Google Health > Food and drink (same kcal, today).

## A2. Meal choice (3 min)
- Every logging screen (photo, say/type, barcode, search) shows Breakfast · Lunch · Snack · Dinner at the top,
  already set from the clock ("Set from the time"): 5-12 breakfast, 12-4 lunch, 4-7 snack (evening), 7 onwards dinner.
- Type or say "for lunch", "nashta", "shaam ki chai" or "raat ka khana": the chip moves ("From what you said").
- Tap a chip yourself: it stays, whatever you type after.
- Save lunch at 9 pm: Google Health shows it at 1:30 pm under Lunch.
- Forgot yesterday: on any logging screen tap Yesterday (or 📅 Other day, up to 30 days back), pick Dinner, log it.
  The confirmation says "· Thu 24 Sep"; History shows it under that day; Google Health shows it on that day at 8:30 pm,
  and that day's calories and macros include it; today's ticker does not change.
PASS if all five behave as described.

## B. Packaged food (10 min)
5. Scan barcode: a pack Open Food Facts knows. PASS if Review shows the nutrition facts first,
   "How much did you eat?" is empty, and Save is off until you enter an amount.
6. Enter an amount. PASS if the totals match amount × per-100 values (check one by hand).
7. Scan the Snackible Jowar Puffs (8908028672105). PASS if it finds values online with a source line,
   or tells you clearly why not ("Google Search did not run" / "no page shows its table").
8. Scan any pack, choose Not found > Photograph the label. PASS if values match the printed table.
9. Packaged bread or biscuits: PASS if Review offers "how many × size" (slices, biscuits).

## C. Search foods (10 min), offline databank
10. Search: roti, daal, bhindi sabji, poha, idli, dahi, paneer, chai, beer, whisky. PASS if each finds a sensible row
    in the first three results, and cooked dishes come before raw ingredients.
11. Roti: set 3 × 18 cm. PASS if the amount is 105 g. Try 15 cm and 23 cm: PASS if the grams change.
12. Dal: PASS if you see ½, 1, 1½, 2 katori chips using YOUR katori size.
13. Poori: PASS if it is about 360 kcal/100 g, not 738 (source line says frying oil corrected).
14. Whisky: "1 large peg 60 ml". PASS if about 135 kcal.

## D. Meal photos (10 min)
15. Take a photo > Meal > Home food. One photo per dish (2-3 photos). Estimate.
    PASS if each dish appears once, grams look sensible, and rows show "Values: <dish> · INDB/IFCT" where matched.
16. On a roti or phulka row: set the count and size. PASS if grams update.
17. Tap "Use AI" on a matched row, then back. PASS if the kcal changes and returns.
18. Untick one item, save. PASS if Google Health shows each ticked item separately.

## E. Voice and typing (5 min), Deepgram Nova-3 multilingual
19. Settings > Voice: paste your Deepgram key > Save. PASS if "Ready. Deepgram accepted the key".
20. Home > Say or type > Speak. Allow the microphone once. Say, mixing freely:
    "gatte ki sabzi with two wheat rotis aur ek katori dahi". Tap Stop.
    PASS if the text appears within ~3 seconds and the dish words are right (Hindi words may come in Devanagari).
21. Say a fully Hindi sentence, then a fully English one. PASS if both come out without touching any setting.
22. Estimate. PASS if items and amounts follow what you said.

## F. Restaurant and menu (5 min)
23. Photo > Meal > Restaurant, name "McDonald's", photo of a McAloo Tikki (or a picture of one).
    PASS if the row says "Published by McDonald's …" or stays an estimate with a range.
24. Scan a menu (any menu photo). PASS if it shows 3 eggetarian dishes within your calories left, with reasons,
    and "I ordered this" opens a meal to save.

## G. History and daily use (10 min)
25. Home > Today list shows what you logged. PASS if it matches.
26. All history: change one amount. PASS if Google Health shows the new value.
27. Delete one entry. PASS if it disappears from Google Health.
28. Star one food. Home > Log again > tap it. PASS if logged; Undo removes it.
29. Put the phone in flight mode: Search foods still works; photo and voice estimate show a clear error.

## H. Accuracy (2 weeks, 2 minutes a day)
30. For 10 home meals: before eating, weigh the plate items on a kitchen scale; log with the app as normal;
    note app grams vs scale grams. Send me the 10 pairs.
    PASS if the app is within 25% of the scale for 7 of 10 meals, total kcal.

## Notes
- Meal numbers are estimates; the app says so. Databank rows come from INDB, IFCT 2017, CoFID (see tools/fooddb/REPORT.md).
- Typical piece weights (roti by diameter, bread slices, idli sizes) are starting points; your weighed values beat them.
