"""Species that can kill a person, with original warning text.

Sources are open encyclopedias and the pipeline's existing curated notes (cited per row).
This is identification and first-aid warning text. It is not a treatment protocol and it
does not describe how to prepare or take any plant or fungus.

Regenerate the committed table with ``python -m openwild_pipeline.deadly_catalog``.
"""
from __future__ import annotations

# toxin id -> warning. kingdom is Plantae unless noted. psychoactive adds the app's legal
# warning and must not carry preparation language.
TOXINS: dict[str, dict] = {
    "amatoxin": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="amatoxins",
        symptoms="Severe vomiting, watery diarrhoea and abdominal pain, then a period of feeling better, then liver and kidney failure.",
        onset="Usually 6–24 hours after eating. Feeling better on the second day does not mean the danger has passed.",
        what_to_do="Seek emergency medical care immediately, even with no symptoms. Tell doctors this may be amatoxin poisoning and keep a sample of the mushroom.",
        severity="Frequently fatal without urgent treatment. Cooking does not make it safe."),
    "orellanine": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="orellanine",
        symptoms="Nausea, headache, intense thirst and frequent urination, then kidney failure.",
        onset="Very delayed, often 2–14 days after eating, so the meal is easy to forget.",
        what_to_do="Seek medical care as soon as you suspect it, even days later. Tell doctors this may be orellanine (webcap) poisoning.",
        severity="Can cause permanent kidney failure or death."),
    "gyromitrin": dict(kingdom="Fungi", route="ingestion; inhalation", toxic_parts="whole mushroom", toxin="gyromitrin",
        symptoms="Vomiting, diarrhoea, headache and dizziness. Severe cases bring seizures and liver damage. Fumes given off while it is cooked can also poison people nearby.",
        onset="Usually several hours after eating, sometimes sooner when the fumes are breathed in.",
        what_to_do="Seek emergency medical care. Tell doctors this may be gyromitrin (false morel) poisoning, and air the room if it was cooked.",
        severity="Can be fatal. Cooking does not reliably make it safe."),
    "muscarine": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="muscarine",
        symptoms="Heavy sweating, salivation, tears, blurred vision, vomiting, diarrhoea, a slow heartbeat and trouble breathing.",
        onset="Fast, often within 15–120 minutes.",
        what_to_do="Seek emergency medical care immediately. Tell doctors this may be muscarine poisoning.",
        severity="Can be fatal, especially if breathing slows."),
    "muscimol": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="muscimol and ibotenic acid", psychoactive=True,
        symptoms="Vomiting, confusion, drowsiness alternating with agitation, muscle twitching and delirium.",
        onset="Usually within 30 minutes to 3 hours.",
        what_to_do="Seek medical care. Keep the person supervised and safe from falls.",
        severity="Deaths have been reported but are uncommon. Potency varies from mushroom to mushroom."),
    "illudin": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="illudins",
        symptoms="Severe vomiting, diarrhoea and cramps that can last a day or more.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, especially if fluids will not stay down or the person is a child.",
        severity="Deaths have been reported but are uncommon. Cooking does not make it safe."),
    "paxillus": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="unidentified immune trigger",
        symptoms="After earlier meals that seemed harmless, a later meal can cause sudden vomiting, collapse and destruction of red blood cells.",
        onset="The dangerous reaction often waits until a repeat meal, then comes on within hours.",
        what_to_do="Seek emergency medical care immediately. Tell doctors about every meal of this mushroom, including earlier ones that seemed fine.",
        severity="Can be fatal. The risk is not limited to the first time it is eaten."),
    "rhabdo": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="compounds that break down muscle",
        symptoms="Muscle pain and weakness, dark urine, then kidney failure.",
        onset="Usually 1–3 days after eating, sometimes after several meals.",
        what_to_do="Seek emergency medical care. Tell doctors about wild mushrooms eaten over the past few days.",
        severity="Can be fatal. Repeated meals are especially dangerous."),
    "satratoxin": dict(kingdom="Fungi", route="ingestion; contact", toxic_parts="whole fungus", toxin="satratoxins (trichothecene)",
        symptoms="Peeling skin, falling blood counts, hair loss and failure of several organs.",
        onset="Delayed, often several days after eating even a small piece.",
        what_to_do="Seek emergency medical care immediately, even if you feel well. Tell doctors this may be poison fire coral. Do not handle it with bare hands.",
        severity="Frequently fatal."),
    "trogia": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="unusual amino acids",
        symptoms="Sudden heart-rhythm failure and very low blood sugar.",
        onset="Can be delayed by hours to days after eating.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Linked to sudden deaths. Cooking does not make it safe."),
    "ergot": dict(kingdom="Fungi", route="ingestion", toxic_parts="fungal bodies on grain", toxin="ergot alkaloids",
        symptoms="Burning pain in the limbs, convulsions, confusion, and gangrene of fingers and toes after eating grain that carries this fungus.",
        onset="Can follow one heavily infected meal, or build over days of contaminated grain.",
        what_to_do="Seek emergency medical care. Stop eating the suspect grain and keep a sample.",
        severity="Outbreaks have killed people. Do not eat dark spur-shaped growths on grain heads."),
    "gi_rare": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="unidentified gastrointestinal toxin",
        symptoms="Severe vomiting, diarrhoea and cramps, with a risk of dangerous dehydration.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, especially for children or if fluids will not stay down.",
        severity="Deaths have been reported but are uncommon."),
    "allenic": dict(kingdom="Fungi", route="ingestion", toxic_parts="whole mushroom", toxin="allenic norleucine and related amino acids",
        symptoms="Vomiting, then kidney failure.",
        onset="Often within hours to a couple of days.",
        what_to_do="Seek emergency medical care. Tell doctors this may be a kidney-toxic amanita and keep a sample.",
        severity="Can cause kidney failure and death."),
    "aconitine": dict(route="ingestion; contact", toxic_parts="all parts, especially roots", toxin="aconitine and related alkaloids",
        symptoms="Tingling and numbness of the mouth and face, vomiting, weakness and a dangerously irregular heartbeat. Sap on the skin can also poison.",
        onset="Fast, from minutes to about an hour.",
        what_to_do="Seek emergency medical care immediately. Do not touch broken roots or leaves with bare hands.",
        severity="Frequently fatal."),
    "delphinium": dict(route="ingestion", toxic_parts="all parts, especially seeds and young growth", toxin="diterpenoid alkaloids",
        symptoms="Tingling, vomiting, weakness and an irregular heartbeat. Severe cases progress to paralysis.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care.",
        severity="Can be fatal."),
    "cardiac": dict(route="ingestion; inhalation", toxic_parts="all parts", toxin="cardiac glycosides",
        symptoms="Nausea, vomiting, abdominal pain, dizziness, a slow or irregular heartbeat and collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care. Heart monitoring may be needed.",
        severity="Can be fatal. Cooking does not make it safe. Do not burn it or use the stems as skewers."),
    "taxine": dict(route="ingestion", toxic_parts="leaves, bark and the seed inside the berry", toxin="taxine alkaloids",
        symptoms="Dizziness, vomiting, a slow heartbeat and collapse. Some people worsen suddenly with little warning.",
        onset="Usually within one to a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Can be fatal. The red flesh of a yew berry is not the dangerous part; the seed inside it is."),
    "cicutoxin": dict(route="ingestion", toxic_parts="whole plant, especially roots", toxin="cicutoxin",
        symptoms="Nausea and vomiting, then violent seizures.",
        onset="Very fast, often within 15–60 minutes.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Often fatal. The roots are especially dangerous and have been mistaken for edible roots."),
    "oenanthe": dict(route="ingestion", toxic_parts="whole plant, especially roots", toxin="oenanthotoxin",
        symptoms="Nausea, salivation, then violent seizures. The face may lock in a grimace.",
        onset="Very fast, often within an hour.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Often fatal. The roots have a smell that has been compared to parsley or celery."),
    "coniine": dict(route="ingestion", toxic_parts="whole plant, especially seeds and roots", toxin="coniine and related alkaloids",
        symptoms="Burning in the mouth, salivation and trembling, then muscle paralysis that can stop breathing.",
        onset="Often within 30 minutes to a few hours.",
        what_to_do="Seek emergency medical care immediately. Breathing support may be needed.",
        severity="Frequently fatal."),
    "foolparsley": dict(route="ingestion", toxic_parts="whole plant", toxin="coniine-like alkaloids",
        symptoms="Burning mouth, vomiting, then weakness that can progress toward paralysis.",
        onset="Often within a few hours.",
        what_to_do="Seek emergency medical care. It grows among edible herbs and is easy to pick by mistake.",
        severity="Can be fatal."),
    "ricin": dict(route="ingestion", toxic_parts="seeds", toxin="ricin",
        symptoms="Severe vomiting, bloody diarrhoea, dehydration and organ failure.",
        onset="Delayed, from several hours to a day or more.",
        what_to_do="Seek emergency medical care immediately, even before symptoms.",
        severity="Chewed or broken seeds can be fatal."),
    "abrin": dict(route="ingestion", toxic_parts="seeds", toxin="abrin",
        symptoms="Severe vomiting, bloody diarrhoea, dehydration and organ failure.",
        onset="Delayed, from several hours to a few days.",
        what_to_do="Seek emergency medical care immediately, even before symptoms.",
        severity="A broken or chewed seed can be fatal. Intact seeds are still a medical emergency."),
    "toxalbumin": dict(route="ingestion", toxic_parts="seeds and sap", toxin="toxalbumins",
        symptoms="Burning in the throat, severe vomiting, diarrhoea and dehydration.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care, especially for a child.",
        severity="Seeds can be fatal. They can look like nuts."),
    "colchicine": dict(route="ingestion", toxic_parts="all parts, especially corms, tubers and seeds", toxin="colchicine",
        symptoms="Severe vomiting and diarrhoea, then failure of several organs.",
        onset="Usually within 2–12 hours. Organ failure can follow over the next days.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Frequently fatal. Cooking does not make it safe. Tubers have been mistaken for edible bulbs."),
    "tropane": dict(route="ingestion", toxic_parts="all parts, especially seeds and roots", toxin="tropane alkaloids", psychoactive=True,
        symptoms="Dry mouth, dilated pupils, blurred vision, a fast heartbeat, fever, confusion, delirium and seizures.",
        onset="Usually within 30 minutes to a few hours. Effects can last a day or more.",
        what_to_do="Seek emergency medical care immediately. Keep the person from wandering.",
        severity="Can be fatal. The amount of toxin varies hugely between plants, so there is no safe amount to guess."),
    "strychnine": dict(route="ingestion", toxic_parts="seeds, bark and roots", toxin="strychnine",
        symptoms="Restlessness, then painful muscle spasms and convulsions while the person stays conscious. Breathing can fail.",
        onset="Usually within 15–60 minutes.",
        what_to_do="Seek emergency medical care immediately. Keep the person quiet if you can do so safely.",
        severity="Frequently fatal."),
    "gelsemine": dict(route="ingestion", toxic_parts="all parts, including nectar", toxin="gelsemine and related alkaloids",
        symptoms="Blurred or double vision, weakness, slow breathing and collapse.",
        onset="Usually within an hour or two.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Can be fatal. Do not taste the flowers or suck the nectar."),
    "nicotine": dict(route="ingestion; contact", toxic_parts="all parts, especially leaves", toxin="nicotine and related alkaloids",
        symptoms="Nausea, vomiting, sweating and confusion, then a slow heartbeat and breathing failure. Wet or crushed leaves on skin can also poison.",
        onset="Often within minutes to an hour.",
        what_to_do="Seek emergency medical care immediately. Wash skin that touched crushed leaves.",
        severity="Can be fatal. Cooking does not make the leaves safe."),
    "cycasin": dict(route="ingestion", toxic_parts="seeds, and raw pith or leaves", toxin="cycasin",
        symptoms="Vomiting and diarrhoea, then liver damage.",
        onset="Usually within hours. Liver injury can worsen over the following days.",
        what_to_do="Seek emergency medical care. Do not eat the seeds.",
        severity="Seeds can be fatal. Animals are often poisoned by the same plant."),
    "grayanotoxin": dict(route="ingestion", toxic_parts="leaves, flowers, nectar and honey made from them", toxin="grayanotoxins",
        symptoms="Salivation, vomiting, low blood pressure, a slow heartbeat and, in severe cases, collapse. Honey from the flowers can cause the same illness.",
        onset="Usually within minutes to a few hours.",
        what_to_do="Seek emergency medical care for collapse, a slow heartbeat or confusion.",
        severity="Can be fatal."),
    "cyanide_kernel": dict(route="ingestion", toxic_parts="seeds, kernels and crushed leaves", toxin="cyanogenic glycosides",
        symptoms="Headache, dizziness, confusion, gasping and collapse after eating seeds, kernels or crushed leaves.",
        onset="Often within minutes to a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Kernels and leaves can be fatal. Where the fruit flesh is a food, the kernel inside the stone is still dangerous."),
    "cyanide_root": dict(route="ingestion", toxic_parts="raw roots, peel and raw leaves", toxin="cyanogenic glycosides",
        symptoms="Dizziness, headache, vomiting, rapid breathing and collapse after eating it raw or poorly prepared.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care for any symptoms.",
        severity="Can be fatal when eaten raw or badly prepared. Bitter plants are the most dangerous."),
    "fluoroacetate": dict(route="ingestion", toxic_parts="all parts", toxin="fluoroacetate",
        symptoms="Vomiting, then seizures and heart-rhythm failure.",
        onset="Often within a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Can be fatal. Grazing animals die from these plants, and people have been poisoned too."),
    "hypoglycin": dict(route="ingestion", toxic_parts="unripe arils, seeds and rind", toxin="hypoglycin",
        symptoms="Violent vomiting, then very low blood sugar, seizures and coma.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Unripe fruit and the seeds can be fatal. Fruit that was forced open is unsafe."),
    "atractyloside": dict(route="ingestion", toxic_parts="roots and the whole plant", toxin="atractyloside",
        symptoms="Vomiting and sweating, then liver and kidney failure.",
        onset="Often within a few hours to a day.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Can be fatal. The root has been mistaken for edible roots."),
    "veratrum": dict(route="ingestion", toxic_parts="all parts, especially roots and bulbs", toxin="veratrum alkaloids",
        symptoms="Vomiting, a slow heartbeat, low blood pressure and collapse.",
        onset="Usually within 30 minutes to a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Can be fatal. Bulbs have been mistaken for edible bulbs."),
    "cytisine": dict(route="ingestion", toxic_parts="seeds, pods and all parts", toxin="cytisine",
        symptoms="Vomiting, sweating, headache and drowsiness. Severe cases bring convulsions.",
        onset="Usually within an hour.",
        what_to_do="Seek emergency medical care, especially for a child.",
        severity="Seeds and pods can be fatal. Children are often poisoned by the pea-like pods."),
    "solanine": dict(route="ingestion", toxic_parts="leaves, green fruit and green tubers", toxin="solanine and related glycoalkaloids",
        symptoms="Headache, vomiting, diarrhoea and confusion. Severe cases end in collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, and emergency care if the person is confused, convulsing or hard to wake.",
        severity="Can be fatal, especially for children."),
    "solanine_tuber": dict(route="ingestion", toxic_parts="green or sprouted tubers, fruits and leaves", toxin="solanine and chaconine",
        symptoms="Headache, vomiting, diarrhoea and confusion after eating green, bitter or sprouted tubers, or the fruits and leaves.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care if the person is confused or hard to wake.",
        severity="Ordinary cooked tubers are food. Green, sprouted or bitter tubers, and the fruits and leaves, can be fatal."),
    "solanine_tomato": dict(route="ingestion", toxic_parts="leaves and stems", toxin="tomatine and related alkaloids",
        symptoms="Vomiting, diarrhoea and drowsiness after swallowing leaves or stems.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care if leaves or stems were swallowed.",
        severity="Ripe fruit is the food. Leaves and stems have caused severe poisoning, including a recorded death."),
    "daphne": dict(route="ingestion", toxic_parts="all parts, especially berries and bark", toxin="daphnetoxin and mezerein",
        symptoms="Burning mouth, vomiting and bloody diarrhoea. Severe cases collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care.",
        severity="Berries and bark can be fatal."),
    "podophyllum": dict(route="ingestion", toxic_parts="root, leaves and seeds", toxin="podophyllotoxin",
        symptoms="Severe vomiting and diarrhoea, then nerve and organ damage.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Can be fatal. The root, leaves and seeds are the dangerous parts."),
    "pyrrolizidine": dict(route="ingestion", toxic_parts="all parts", toxin="pyrrolizidine alkaloids",
        symptoms="Abdominal pain and a swollen, failing liver. The damage can continue after the meal.",
        onset="Often delayed by days.",
        what_to_do="Seek medical care if this plant was swallowed. Tell doctors it contains pyrrolizidine alkaloids.",
        severity="A large meal, or repeated meals, can be fatal."),
    "pennyroyal": dict(route="ingestion", toxic_parts="leaves and the oil", toxin="pulegone",
        symptoms="Vomiting, then liver failure, bleeding and collapse.",
        onset="Often within a few hours, with liver injury over the next days.",
        what_to_do="Seek emergency medical care if the plant or its oil was swallowed.",
        severity="The oil has caused deaths. Do not swallow the plant."),
    "oxalate": dict(route="ingestion", toxic_parts="all parts", toxin="calcium oxalate crystals",
        symptoms="Immediate burning and swelling of the mouth and throat that can block breathing, plus vomiting.",
        onset="Within minutes of a bite.",
        what_to_do="Seek emergency medical care if breathing or swallowing is difficult, especially for a child.",
        severity="Deaths are uncommon, but swelling of the airway can be life-threatening."),
    "urushiol": dict(route="contact; inhalation", toxic_parts="all parts, and smoke from burning it", toxin="urushiol",
        symptoms="An itchy, blistering rash where skin touched the plant. Smoke from burning it can inflame the lungs and make breathing difficult.",
        onset="The rash usually appears 12–72 hours after contact. Smoke injury can be faster.",
        what_to_do="Wash skin and clothing with soap and water as soon as possible. Seek urgent care for breathing problems or a rash on the face.",
        severity="The rash itself is usually not fatal. Smoke from burning the plant has caused life-threatening lung injury. Never burn it."),
    "phototoxic": dict(route="contact", toxic_parts="sap", toxin="furanocoumarins",
        symptoms="Sap on skin, followed by sunlight, causes deep burns and blisters. Eyes can be badly injured.",
        onset="Burns appear over the next day on skin that met both the sap and the sun.",
        what_to_do="Wash the sap off immediately and keep that skin out of sunlight. Seek urgent care for a large burn or any sap in the eye.",
        severity="Deaths are uncommon. Burns can be severe enough to need hospital care, and an eye injury can be permanent."),
    "manchineel": dict(route="ingestion; contact; inhalation", toxic_parts="fruit, sap and smoke", toxin="irritant terpenes",
        symptoms="Sap causes blistering burns and severe eye injury. Swallowing the fruit burns the mouth and throat and can lead to collapse. Smoke from the wood injures the lungs.",
        onset="Skin pain is rapid. Illness after eating starts within minutes to a few hours.",
        what_to_do="Rinse sap off the skin and seek emergency medical care for eye exposure, swallowed fruit or trouble breathing.",
        severity="Eating the fruit can be fatal. Do not stand under the tree in the rain, and do not burn the wood."),
    "ongaonga": dict(route="contact", toxic_parts="stinging hairs", toxin="histamine, acetylcholine and serotonin",
        symptoms="The hairs inject a venom that causes extreme pain and, in severe cases, trouble breathing and collapse.",
        onset="Pain is immediate.",
        what_to_do="Seek emergency medical care for a large sting or any trouble breathing.",
        severity="Stings have caused deaths. Do not touch the plant."),
    "gympie": dict(route="contact", toxic_parts="stinging hairs", toxin="moroidin and related peptide toxins",
        symptoms="The hairs cause pain that can last for weeks or months, with swelling and, in severe cases, trouble breathing.",
        onset="Pain is immediate and can intensify over hours.",
        what_to_do="Seek emergency medical care. Do not rub the sting.",
        severity="The pain has been followed by death in rare cases. Do not touch the plant."),
    "tutin": dict(route="ingestion", toxic_parts="all parts, especially berries, and honey from bees that visited it", toxin="tutin",
        symptoms="Nausea, then seizures. Honey from bees that foraged on the plant can cause the same illness.",
        onset="Often within a few hours.",
        what_to_do="Seek emergency medical care for seizures or collapse.",
        severity="Can be fatal."),
    "physostigmine": dict(route="ingestion", toxic_parts="seeds", toxin="physostigmine",
        symptoms="Vomiting, sweating, a slow heartbeat, weakness and trouble breathing.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="The beans can be fatal."),
    "curare": dict(route="contact; ingestion", toxic_parts="bark and roots", toxin="tubocurarine",
        symptoms="Muscle weakness that can stop breathing if the sap enters a wound. Swallowing it is poorly absorbed but it is still not food.",
        onset="Rapid if it enters a wound.",
        what_to_do="Seek emergency medical care for weakness after a wound contaminated with this plant.",
        severity="A wound poisoned with it can be fatal."),
    "antiaris": dict(route="contact; ingestion", toxic_parts="latex", toxin="antiarin (cardiac glycoside)",
        symptoms="Latex in a wound causes vomiting, an irregular heartbeat and collapse. Swallowing the latex is also dangerous.",
        onset="Can be rapid when latex enters a wound.",
        what_to_do="Seek emergency medical care for a wound contaminated with the latex, or if it was swallowed.",
        severity="Arrow poison made from this tree can kill. Do not get the latex in a cut."),
    "dioscorine": dict(route="ingestion", toxic_parts="tuber", toxin="dioscorine",
        symptoms="A burning throat, vomiting, dizziness and, in severe cases, convulsions and collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care if the tuber was eaten raw or only partly processed.",
        severity="The raw tuber can be fatal."),
    "pangium": dict(route="ingestion", toxic_parts="seeds and unprocessed fruit", toxin="cyanogenic glycosides",
        symptoms="Headache, gasping, confusion and collapse.",
        onset="Often within a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="Raw seeds can be fatal. Traditional processing is long and is not described here."),
    "cleistanthus": dict(route="ingestion", toxic_parts="leaves", toxin="cleistanthin and related compounds",
        symptoms="Very low blood potassium, an irregular heartbeat, kidney injury and collapse.",
        onset="Often delayed by several hours to a day.",
        what_to_do="Seek emergency medical care immediately, even if you feel well.",
        severity="Frequently fatal."),
    "saponin": dict(route="ingestion", toxic_parts="seeds and all parts", toxin="saponins and related glycosides",
        symptoms="Vomiting, diarrhoea and weakness. Severe cases bring paralysis or collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, and emergency care if the person is weak, confused or a child.",
        severity="Seeds and large amounts can be fatal."),
    "phytolacca": dict(route="ingestion", toxic_parts="root, leaves and unripe fruit", toxin="phytolaccatoxin",
        symptoms="A burning mouth, severe vomiting and diarrhoea. Large amounts can cause trouble breathing and collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care for a child or if breathing is affected.",
        severity="The root is the most dangerous part and can be fatal."),
    "lantana": dict(route="ingestion", toxic_parts="green berries and leaves", toxin="lantadenes",
        symptoms="Vomiting, weakness and liver injury. Children usually eat the berries.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, especially for a child who ate the berries.",
        severity="Can be fatal."),
    "melia": dict(route="ingestion", toxic_parts="fruit, bark and leaves", toxin="meliatoxins",
        symptoms="Vomiting, diarrhoea, a slow heartbeat and, in severe cases, seizures and collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care, especially for a child.",
        severity="The fruit and bark can be fatal."),
    "karwinskia": dict(route="ingestion", toxic_parts="fruit", toxin="anthracenones",
        symptoms="Weakness that starts in the legs and can progress until breathing fails.",
        onset="Delayed, often by several days, so the fruit is easy to forget.",
        what_to_do="Seek emergency medical care for weakness after eating the fruit, even days later.",
        severity="Can be fatal."),
    "snakeroot": dict(route="ingestion", toxic_parts="all parts, and milk or meat from animals that grazed it", toxin="tremetone",
        symptoms="Weakness, vomiting and muscle tremors. People have also been poisoned by milk or meat from animals that ate this plant.",
        onset="Often delayed by hours to days.",
        what_to_do="Seek emergency medical care. Mention milk or meat from animals that may have grazed this plant.",
        severity="Can be fatal."),
    "argemone": dict(route="ingestion", toxic_parts="seeds and oil pressed from them", toxin="sanguinarine",
        symptoms="Swelling, heart failure and severe eye injury after food was cooked in oil that contained the seeds.",
        onset="Can build over days.",
        what_to_do="Seek emergency medical care. Stop eating the suspect oil and keep a sample.",
        severity="Outbreaks have killed people."),
    "mistletoe": dict(route="ingestion", toxic_parts="berries and leaves", toxin="phoratoxins",
        symptoms="Vomiting, diarrhoea and a slow heartbeat after eating the berries.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, and emergency care for a child or if the heartbeat feels slow.",
        severity="A large amount can be fatal, especially for a child."),
    "euonymus": dict(route="ingestion", toxic_parts="fruit, seeds and leaves", toxin="cardenolides and alkaloids",
        symptoms="Vomiting, diarrhoea and weakness. Severe cases injure the liver and kidneys.",
        onset="Often delayed by about half a day.",
        what_to_do="Seek medical care.",
        severity="The fruit can be fatal."),
    "duranta": dict(route="ingestion", toxic_parts="berries and leaves", toxin="saponins",
        symptoms="Vomiting and drowsiness. Severe cases bring convulsions after the berries are eaten.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care, especially for a child.",
        severity="The berries can be fatal."),
    "opioid": dict(route="ingestion", toxic_parts="latex and unripe pods", toxin="morphine and related alkaloids", psychoactive=True,
        symptoms="Drowsiness, pinpoint pupils, nausea and slow, shallow breathing that can stop.",
        onset="Usually within an hour.",
        what_to_do="Seek emergency medical care immediately for slow breathing or if the person cannot be woken.",
        severity="Can be fatal. The latex and unripe pods are the dangerous parts."),
    "boophone": dict(route="ingestion", toxic_parts="bulb", toxin="crinine alkaloids", psychoactive=True,
        symptoms="Vomiting, confusion, a weak heartbeat and collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care immediately.",
        severity="The bulb can be fatal."),
    "lycorine": dict(route="ingestion", toxic_parts="bulb", toxin="lycorine",
        symptoms="Severe vomiting and diarrhoea. Large amounts can cause collapse.",
        onset="Usually within a few hours.",
        what_to_do="Seek medical care, and emergency care if the person is a child or cannot keep fluids down.",
        severity="Deaths are uncommon. The bulb is the dangerous part and has killed when eaten in quantity."),
    "actaea": dict(route="ingestion", toxic_parts="berries and roots", toxin="protoanemonin and related compounds",
        symptoms="Burning mouth, vomiting, diarrhoea and dizziness. Severe cases affect the heart.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care if berries or roots were eaten.",
        severity="Berries have caused severe poisoning and can be fatal."),
    "helleborus": dict(route="ingestion", toxic_parts="all parts", toxin="helleborin and protoanemonin",
        symptoms="Burning mouth, vomiting and an irregular heartbeat.",
        onset="Usually within a few hours.",
        what_to_do="Seek emergency medical care.",
        severity="Can be fatal."),
    "protoanemonin": dict(route="ingestion; contact", toxic_parts="all parts", toxin="protoanemonin",
        symptoms="Burning of the mouth and skin, vomiting and, in severe cases, an irregular heartbeat.",
        onset="Usually within a few hours of swallowing. Skin pain is faster.",
        what_to_do="Seek medical care if it was swallowed.",
        severity="Can be severe. Deaths are uncommon."),
}


def _row(name, tox, common="", family="", source="", aliases=""):
    return {
        "scientific_name": name,
        "toxin_id": tox,
        "common_name": common,
        "family": family,
        "source": source or f"Wikipedia: {name}",
        "aliases": aliases,
    }


def _many(tox, family, rows):
    """rows: (name, common, source-or-None, aliases-or-None) or (name, common)."""
    out = []
    for item in rows:
        name, common = item[0], item[1]
        source = item[2] if len(item) > 2 and item[2] else ""
        aliases = item[3] if len(item) > 3 and item[3] else ""
        out.append(_row(name, tox, common, family, source, aliases))
    return out


WIKI_FUNGI = "Wikipedia: List of deadly mushroom species"
WIKI_PLANTS = "Wikipedia: List of poisonous plants"
WIKIDATA = "Wikidata P789 (deadly mushroom)"


def species_rows() -> list[dict]:
    """Every species in the deadly collection, before GBIF de-duplication."""
    f = WIKI_FUNGI
    p = WIKI_PLANTS
    rows: list[dict] = []
    rows += _many("amatoxin", "Amanitaceae", [
        ("Amanita phalloides", "death cap", f, ""),
        ("Amanita amerivirosa", "destroying angel", f, ""),
        ("Amanita arocheae", "Latin American death cap", f"{f}; {WIKIDATA}", ""),
        ("Amanita bisporigera", "eastern destroying angel", f"{f}; {WIKIDATA}", ""),
        ("Amanita exitialis", "Guangzhou destroying angel", f"{f}; {WIKIDATA}", ""),
        ("Amanita fuliginea", "East Asian brown death cap", f"{f}; {WIKIDATA}", ""),
        ("Amanita magnivelaris", "great felt skirt destroying angel", f"{f}; {WIKIDATA}", ""),
        ("Amanita ocreata", "western destroying angel", f"{f}; {WIKIDATA}", ""),
        ("Amanita subpallidorosea", "", f"{f}; {WIKIDATA}", ""),
        ("Amanita pallidorosea", "", "Wikipedia: Amanita pallidorosea", ""),
        ("Amanita subjunquillea", "East Asian death cap", f"{f}; {WIKIDATA}", ""),
        ("Amanita verna", "fool's mushroom", f"{f}; {WIKIDATA}", ""),
        ("Amanita virosa", "European destroying angel", f"{f}; {WIKIDATA}", ""),
        ("Amanita virosiformis", "", WIKIDATA, ""),
    ])
    rows += _many("allenic", "Amanitaceae", [
        ("Amanita smithiana", "Smith's lepidella", f, ""),
        ("Amanita sphaerobulbosa", "", f"{f}; {WIKIDATA}", ""),
        ("Amanita proxima", "", "Wikipedia: Amanita proxima", ""),
        ("Amanita pseudoporphyria", "", "Wikipedia: Amanita pseudoporphyria", ""),
    ])
    rows += _many("amatoxin", "Hymenogastraceae", [
        ("Galerina marginata", "autumn skullcap", f"{f}; {WIKIDATA}", "Galerina autumnalis"),
        ("Galerina sulciceps", "", f"{f}; {WIKIDATA}", ""),
        ("Galerina venenata", "", "Wikipedia: Galerina venenata", ""),
    ])
    rows += _many("amatoxin", "Agaricaceae", [
        ("Lepiota brunneoincarnata", "deadly dapperling", f"{f}; {WIKIDATA}", ""),
        ("Lepiota brunneolilacea", "star dapperling", f, ""),
        ("Lepiota castanea", "chestnut dapperling", f, ""),
        ("Lepiota helveola", "", f"{f}; {WIKIDATA}", ""),
        ("Lepiota subincarnata", "deadly parasol", f"{f}; {WIKIDATA}", ""),
        ("Lepiota josserandii", "", "Wikipedia: Lepiota josserandii", ""),
        ("Lepiota elaiophylla", "", "Wikipedia: Lepiota elaiophylla", ""),
        ("Lepiota xanthophylla", "", "Wikipedia: Lepiota xanthophylla", ""),
        ("Lepiota locanensis", "", "Wikipedia: Lepiota locanensis", ""),
        ("Lepiota lilacea", "", "Wikipedia: Lepiota lilacea", ""),
    ])
    rows += _many("amatoxin", "Bolbitiaceae", [
        ("Pholiotina rugosa", "", f, "Conocybe filaris; Conocybe rugosa"),
    ])
    rows += _many("orellanine", "Cortinariaceae", [
        ("Cortinarius orellanus", "fool's webcap", f, ""),
        ("Cortinarius rubellus", "deadly webcap", f"{f}; {WIKIDATA}", "Cortinarius speciosissimus; Cortinarius orellanoides"),
        ("Cortinarius eartoxicus", "", f, ""),
        ("Cortinarius gentilis", "", WIKIDATA, ""),
        ("Cortinarius orellanosus", "", "Wikipedia: Cortinarius orellanosus", ""),
        ("Calonarius splendens", "splendid webcap", f, "Cortinarius splendens"),
    ])
    rows += _many("gyromitrin", "Discinaceae", [
        ("Gyromitra esculenta", "false morel", f, ""),
        ("Gyromitra ambigua", "", "Wikipedia: Gyromitra ambigua", ""),
        ("Gyromitra infula", "", "Wikipedia: Gyromitra infula", ""),
    ])
    rows += _many("muscarine", "Inocybaceae", [
        ("Inocybe erubescens", "deadly fibrecap", f, "Inosperma erubescens; Inocybe patouillardii"),
    ])
    rows += _many("muscarine", "Clitocybaceae", [
        ("Clitocybe dealbata", "ivory funnel", f, ""),
        ("Clitocybe rivulosa", "false champignon", f"{f}; {WIKIDATA}", ""),
    ])
    rows += _many("muscimol", "Amanitaceae", [
        ("Amanita muscaria", "fly agaric", f, ""),
        ("Amanita pantherina", "panther cap", "Wikipedia: Amanita pantherina", ""),
    ])
    rows += _many("illudin", "Omphalotaceae", [
        ("Omphalotus illudens", "jack-o'-lantern", f, ""),
        ("Omphalotus japonicus", "jack-o'-lantern", f, "Omphalotus guepiniformis"),
        ("Omphalotus olearius", "jack-o'-lantern", "Wikipedia: Omphalotus olearius", ""),
        ("Omphalotus nidiformis", "ghost fungus", "Wikipedia: Omphalotus nidiformis", ""),
    ])
    rows += _many("paxillus", "Paxillaceae", [
        ("Paxillus involutus", "brown roll-rim", f"{f}; {WIKIDATA}", ""),
    ])
    rows += _many("rhabdo", "Russulaceae", [
        ("Russula subnigricans", "", f, ""),
    ])
    rows += _many("rhabdo", "Tricholomataceae", [
        ("Tricholoma equestre", "yellow knight", f, "Tricholoma flavovirens"),
    ])
    rows += _many("satratoxin", "Hypocreaceae", [
        ("Trichoderma cornu-damae", "poison fire coral", f, "Podostroma cornu-damae"),
    ])
    rows += _many("trogia", "Marasmiaceae", [
        ("Trogia venenata", "little white", f"{f}; {WIKIDATA}", ""),
    ])
    rows += _many("ergot", "Clavicipitaceae", [
        ("Claviceps purpurea", "ergot", f, ""),
    ])
    rows += _many("gi_rare", "", [
        ("Chlorophyllum molybdites", "green-spored parasol", f, ""),
        ("Entoloma sinuatum", "livid pinkgill", f, ""),
        ("Hypholoma fasciculare", "sulphur tuft", f, ""),
        ("Lactarius torminosus", "woolly milkcap", f, ""),
        ("Rubroboletus pulcherrimus", "", f, ""),
        ("Rubroboletus satanas", "Satan's bolete", "Wikipedia: Rubroboletus satanas", "Boletus satanas"),
    ])
    # Families for the gi_rare block that _many left blank: set below if empty.
    _families = {
        "Chlorophyllum molybdites": "Agaricaceae",
        "Entoloma sinuatum": "Entolomataceae",
        "Hypholoma fasciculare": "Strophariaceae",
        "Lactarius torminosus": "Russulaceae",
        "Rubroboletus pulcherrimus": "Boletaceae",
        "Rubroboletus satanas": "Boletaceae",
    }
    for row in rows:
        if not row["family"] and row["scientific_name"] in _families:
            row["family"] = _families[row["scientific_name"]]

    rows += _many("aconitine", "Ranunculaceae", [
        ("Aconitum napellus", "monkshood", p, ""),
        ("Aconitum ferox", "Indian aconite", "Wikipedia: Aconitum ferox", ""),
        ("Aconitum carmichaelii", "Chinese aconite", "Wikipedia: Aconitum carmichaelii", ""),
        ("Aconitum lycoctonum", "wolfsbane", "Wikipedia: Aconitum lycoctonum", "Aconitum vulparia"),
        ("Aconitum variegatum", "", "Wikipedia: Aconitum variegatum", ""),
        ("Aconitum japonicum", "", "Wikipedia: Aconitum japonicum", ""),
        ("Aconitum kusnezoffii", "", "Wikipedia: Aconitum kusnezoffii", ""),
        ("Aconitum uncinatum", "", "Wikipedia: Aconitum uncinatum", ""),
        ("Aconitum columbianum", "", "Wikipedia: Aconitum columbianum", ""),
        ("Aconitum septentrionale", "", "Wikipedia: Aconitum septentrionale", ""),
        ("Aconitum anthora", "yellow monkshood", "Wikipedia: Aconitum anthora", ""),
        ("Aconitum koreanum", "", "Wikipedia: Aconitum koreanum", ""),
        ("Aconitum brachypodum", "", "Wikipedia: Aconitum brachypodum", ""),
        ("Aconitum hemsleyanum", "", "Wikipedia: Aconitum hemsleyanum", ""),
        ("Aconitum tauricum", "", "Wikipedia: Aconitum tauricum", ""),
        ("Aconitum degenii", "", "Wikipedia: Aconitum degenii", ""),
        ("Aconitum sinomontanum", "", "Wikipedia: Aconitum sinomontanum", ""),
    ])
    rows += _many("delphinium", "Ranunculaceae", [
        ("Delphinium barbeyi", "", "Wikipedia: Delphinium barbeyi", ""),
        ("Delphinium glaucum", "", "Wikipedia: Delphinium glaucum", ""),
        ("Delphinium elatum", "", "Wikipedia: Delphinium elatum", ""),
        ("Delphinium bicolor", "", "Wikipedia: Delphinium bicolor", ""),
        ("Delphinium trolliifolium", "", "Wikipedia: Delphinium trolliifolium", ""),
        ("Consolida ajacis", "rocket larkspur", p, "Consolida ambigua; Delphinium ajacis"),
        ("Consolida regalis", "forking larkspur", "Wikipedia: Consolida regalis", ""),
    ])
    rows += _many("cardiac", "Apocynaceae", [
        ("Nerium oleander", "oleander", p, ""),
        ("Cerbera odollam", "suicide tree", p, ""),
        ("Cerbera manghas", "sea mango", "Wikipedia: Cerbera manghas", ""),
        ("Cascabela thevetia", "yellow oleander", p, "Thevetia peruviana"),
        ("Acokanthera oppositifolia", "bushman's poison", "Wikipedia: Acokanthera oppositifolia", ""),
        ("Acokanthera oblongifolia", "", "Wikipedia: Acokanthera oblongifolia", ""),
        ("Acokanthera schimperi", "arrow poison tree", "Wikipedia: Acokanthera schimperi", ""),
        ("Strophanthus gratus", "", p, ""),
        ("Strophanthus kombe", "", "Wikipedia: Strophanthus kombe", ""),
        ("Strophanthus hispidus", "", "Wikipedia: Strophanthus hispidus", ""),
        ("Calotropis gigantea", "crown flower", "Wikipedia: Calotropis gigantea", ""),
        ("Calotropis procera", "apple of Sodom", "Wikipedia: Calotropis procera", ""),
        ("Adenium obesum", "desert rose", p, ""),
        ("Cryptostegia grandiflora", "rubber vine", "Wikipedia: Cryptostegia grandiflora", ""),
        ("Cryptostegia madagascariensis", "", "Wikipedia: Cryptostegia madagascariensis", ""),
        ("Asclepias curassavica", "bloodflower", p, ""),
        ("Apocynum cannabinum", "dogbane", "Wikipedia: Apocynum cannabinum", ""),
    ])
    rows += _many("cardiac", "Asparagaceae", [
        ("Convallaria majalis", "lily of the valley", p, ""),
        ("Convallaria keiskei", "", "Wikipedia: Convallaria keiskei", ""),
        ("Drimia maritima", "sea squill", "Wikipedia: Drimia maritima", "Urginea maritima"),
        ("Bowiea volubilis", "climbing onion", "Wikipedia: Bowiea volubilis", ""),
        ("Ornithogalum umbellatum", "star of Bethlehem", "Wikipedia: Ornithogalum umbellatum", ""),
        ("Ornithogalum saundersiae", "", "Wikipedia: Ornithogalum saundersiae", ""),
    ])
    rows += _many("cardiac", "Plantaginaceae", [
        ("Digitalis purpurea", "foxglove", p, ""),
        ("Digitalis lanata", "Grecian foxglove", "Wikipedia: Digitalis lanata", ""),
        ("Digitalis grandiflora", "", "Wikipedia: Digitalis grandiflora", ""),
        ("Digitalis lutea", "", "Wikipedia: Digitalis lutea", ""),
        ("Digitalis ferruginea", "", "Wikipedia: Digitalis ferruginea", ""),
        ("Digitalis obscura", "", "Wikipedia: Digitalis obscura", ""),
        ("Digitalis laevigata", "", "Wikipedia: Digitalis laevigata", ""),
    ])
    rows += _many("cardiac", "Brassicaceae", [
        ("Erysimum cheiri", "wallflower", p, ""),
    ])
    rows += _many("taxine", "Taxaceae", [
        ("Taxus baccata", "English yew", p, ""),
        ("Taxus cuspidata", "Japanese yew", "Wikipedia: Taxus cuspidata", ""),
        ("Taxus brevifolia", "Pacific yew", "Wikipedia: Taxus brevifolia", ""),
        ("Taxus canadensis", "Canada yew", "Wikipedia: Taxus canadensis", ""),
        ("Taxus wallichiana", "Himalayan yew", "Wikipedia: Taxus wallichiana", ""),
        ("Taxus chinensis", "", "Wikipedia: Taxus chinensis", ""),
        ("Taxus floridana", "Florida yew", "Wikipedia: Taxus floridana", ""),
        ("Taxus globosa", "", "Wikipedia: Taxus globosa", ""),
    ])
    rows += _many("cicutoxin", "Apiaceae", [
        ("Cicuta maculata", "water hemlock", p, ""),
        ("Cicuta douglasii", "western water hemlock", "Wikipedia: Cicuta douglasii", ""),
        ("Cicuta virosa", "cowbane", "Wikipedia: Cicuta virosa", ""),
        ("Cicuta bulbifera", "", "Wikipedia: Cicuta bulbifera", ""),
    ])
    rows += _many("oenanthe", "Apiaceae", [
        ("Oenanthe crocata", "hemlock water-dropwort", p, ""),
        ("Oenanthe aquatica", "fine-leaved water-dropwort", "Wikipedia: Oenanthe aquatica", ""),
    ])
    rows += _many("coniine", "Apiaceae", [
        ("Conium maculatum", "poison hemlock", p, ""),
    ])
    rows += _many("foolparsley", "Apiaceae", [
        ("Aethusa cynapium", "fool's parsley", "Wikipedia: Aethusa cynapium", ""),
    ])
    rows += _many("ricin", "Euphorbiaceae", [
        ("Ricinus communis", "castor bean", p, ""),
    ])
    rows += _many("abrin", "Fabaceae", [
        ("Abrus precatorius", "rosary pea", p, ""),
    ])
    rows += _many("toxalbumin", "Euphorbiaceae", [
        ("Jatropha curcas", "physic nut", "Wikipedia: Jatropha curcas", ""),
        ("Jatropha multifida", "coral plant", "Wikipedia: Jatropha multifida", ""),
        ("Hura crepitans", "sandbox tree", "Wikipedia: Hura crepitans", ""),
        ("Croton tiglium", "purging croton", "Wikipedia: Croton tiglium", ""),
    ])
    rows += _many("toxalbumin", "Euphorbiaceae", [
        ("Vernicia fordii", "tung tree", p, "Aleurites fordii"),
    ])
    rows += _many("colchicine", "Colchicaceae", [
        ("Colchicum autumnale", "autumn crocus", p, ""),
        ("Colchicum speciosum", "", "Wikipedia: Colchicum speciosum", ""),
        ("Colchicum bulbocodium", "", "Wikipedia: Colchicum bulbocodium", ""),
        ("Colchicum variegatum", "", "Wikipedia: Colchicum variegatum", ""),
        ("Colchicum cilicicum", "", "Wikipedia: Colchicum cilicicum", ""),
        ("Gloriosa superba", "flame lily", p, ""),
        ("Sandersonia aurantiaca", "Christmas bells", "Wikipedia: Sandersonia aurantiaca", ""),
    ])
    rows += _many("tropane", "Solanaceae", [
        ("Atropa belladonna", "deadly nightshade", p, ""),
        ("Atropa baetica", "", "Wikipedia: Atropa baetica", ""),
        ("Datura stramonium", "jimsonweed", p, ""),
        ("Datura metel", "", "Wikipedia: Datura metel", ""),
        ("Datura innoxia", "", "Wikipedia: Datura innoxia", "Datura inoxia"),
        ("Datura ferox", "", "Wikipedia: Datura ferox", ""),
        ("Datura wrightii", "", "Wikipedia: Datura wrightii", ""),
        ("Datura discolor", "", "Wikipedia: Datura discolor", ""),
        ("Brugmansia suaveolens", "angel's trumpet", p, ""),
        ("Brugmansia arborea", "angel's trumpet", "Wikipedia: Brugmansia arborea", ""),
        ("Brugmansia aurea", "angel's trumpet", "Wikipedia: Brugmansia aurea", ""),
        ("Brugmansia sanguinea", "angel's trumpet", "Wikipedia: Brugmansia sanguinea", ""),
        ("Brugmansia versicolor", "angel's trumpet", "Wikipedia: Brugmansia versicolor", ""),
        ("Brugmansia candida", "angel's trumpet", "Wikipedia: Brugmansia candida", ""),
        ("Hyoscyamus niger", "henbane", p, ""),
        ("Hyoscyamus muticus", "Egyptian henbane", "Wikipedia: Hyoscyamus muticus", ""),
        ("Hyoscyamus albus", "", "Wikipedia: Hyoscyamus albus", ""),
        ("Mandragora officinarum", "mandrake", p, ""),
        ("Mandragora autumnalis", "", "Wikipedia: Mandragora autumnalis", ""),
        ("Scopolia carniolica", "", p, ""),
        ("Scopolia japonica", "", "Wikipedia: Scopolia japonica", ""),
        ("Duboisia myoporoides", "", "Wikipedia: Duboisia myoporoides", ""),
        ("Duboisia hopwoodii", "pituri", "Wikipedia: Duboisia hopwoodii", ""),
        ("Duboisia leichhardtii", "", "Wikipedia: Duboisia leichhardtii", ""),
        ("Latua pubiflora", "", "Wikipedia: Latua pubiflora", ""),
    ])
    rows += _many("strychnine", "Loganiaceae", [
        ("Strychnos nux-vomica", "strychnine tree", p, ""),
        ("Strychnos ignatii", "Saint Ignatius bean", "Wikipedia: Strychnos ignatii", ""),
        ("Strychnos toxifera", "", "Wikipedia: Strychnos toxifera", ""),
    ])
    rows += _many("gelsemine", "Gelsemiaceae", [
        ("Gelsemium sempervirens", "Carolina jessamine", p, ""),
        ("Gelsemium elegans", "", "Wikipedia: Gelsemium elegans", ""),
        ("Gelsemium rankinii", "", "Wikipedia: Gelsemium rankinii", ""),
    ])
    rows += _many("nicotine", "Solanaceae", [
        ("Nicotiana glauca", "tree tobacco", p, ""),
        ("Nicotiana tabacum", "tobacco", "Wikipedia: Nicotiana tabacum", ""),
        ("Nicotiana rustica", "", "Wikipedia: Nicotiana rustica", ""),
        ("Nicotiana suaveolens", "", "Wikipedia: Nicotiana suaveolens", ""),
    ])
    rows += _many("cycasin", "Cycadaceae", [
        ("Cycas revoluta", "sago palm", p, ""),
        ("Cycas circinalis", "", "Wikipedia: Cycas circinalis", ""),
        ("Cycas rumphii", "", "Wikipedia: Cycas rumphii", ""),
        ("Cycas micronesica", "", "Wikipedia: Cycas micronesica", ""),
        ("Cycas thouarsii", "", "Wikipedia: Cycas thouarsii", ""),
    ])
    rows += _many("cycasin", "Zamiaceae", [
        ("Zamia integrifolia", "coontie", "Wikipedia: Zamia integrifolia", ""),
        ("Zamia furfuracea", "cardboard palm", "Wikipedia: Zamia furfuracea", ""),
        ("Macrozamia communis", "", "Wikipedia: Macrozamia communis", ""),
        ("Macrozamia riedlei", "", "Wikipedia: Macrozamia riedlei", ""),
        ("Encephalartos altensteinii", "", "Wikipedia: Encephalartos altensteinii", ""),
        ("Dioon edule", "", "Wikipedia: Dioon edule", ""),
        ("Lepidozamia peroffskyana", "", "Wikipedia: Lepidozamia peroffskyana", ""),
        ("Bowenia spectabilis", "", "Wikipedia: Bowenia spectabilis", ""),
    ])
    rows += _many("grayanotoxin", "Ericaceae", [
        ("Kalmia latifolia", "mountain laurel", p, ""),
        ("Kalmia angustifolia", "sheep laurel", "Wikipedia: Kalmia angustifolia", ""),
        ("Kalmia polifolia", "", "Wikipedia: Kalmia polifolia", ""),
        ("Pieris japonica", "Japanese pieris", p, ""),
        ("Pieris floribunda", "", "Wikipedia: Pieris floribunda", ""),
        ("Rhododendron ponticum", "", "Wikipedia: Rhododendron ponticum", ""),
        ("Rhododendron luteum", "yellow azalea", p, ""),
        ("Rhododendron ferrugineum", "alpenrose", p, ""),
        ("Rhododendron maximum", "", "Wikipedia: Rhododendron maximum", ""),
        ("Rhododendron macrophyllum", "", "Wikipedia: Rhododendron macrophyllum", ""),
        ("Rhododendron tomentosum", "Labrador tea", p, ""),
        ("Leucothoe fontanesiana", "", "Wikipedia: Leucothoe fontanesiana", ""),
        ("Leucothoe axillaris", "", "Wikipedia: Leucothoe axillaris", ""),
        ("Andromeda polifolia", "bog rosemary", "Wikipedia: Andromeda polifolia", ""),
        ("Lyonia ligustrina", "", "Wikipedia: Lyonia ligustrina", ""),
    ])
    rows += _many("cyanide_kernel", "Rosaceae", [
        ("Prunus laurocerasus", "cherry laurel", p, ""),
        ("Prunus armeniaca", "apricot", "Wikipedia: Apricot kernel", ""),
        ("Prunus dulcis", "almond", "Wikipedia: Almond", ""),
        ("Prunus serotina", "black cherry", "Wikipedia: Prunus serotina", ""),
        ("Prunus virginiana", "chokecherry", "Wikipedia: Prunus virginiana", ""),
        ("Prunus padus", "bird cherry", p, ""),
    ])
    rows += _many("cyanide_root", "Euphorbiaceae", [
        ("Manihot esculenta", "cassava", "Wikipedia: Cassava", ""),
    ])
    rows += _many("pangium", "Achariaceae", [
        ("Pangium edule", "football fruit", "Wikipedia: Pangium edule", ""),
    ])
    rows += _many("fluoroacetate", "Dichapetalaceae", [
        ("Dichapetalum cymosum", "gifblaar", p, ""),
        ("Dichapetalum toxicarium", "", "Wikipedia: Dichapetalum toxicarium", ""),
    ])
    rows += _many("fluoroacetate", "Fabaceae", [
        ("Gastrolobium bilobum", "", "Wikipedia: Gastrolobium bilobum", ""),
        ("Gastrolobium calycinum", "", "Wikipedia: Gastrolobium calycinum", ""),
        ("Gastrolobium grandiflorum", "", "Wikipedia: Gastrolobium grandiflorum", ""),
        ("Acacia georginae", "Georgina gidgee", "Wikipedia: Acacia georginae", ""),
    ])
    rows += _many("hypoglycin", "Sapindaceae", [
        ("Blighia sapida", "ackee", "Wikipedia: Ackee", ""),
    ])
    rows += _many("atractyloside", "Asteraceae", [
        ("Atractylis gummifera", "pine thistle", "Wikipedia: Atractylis gummifera", "Carlina gummifera"),
        ("Callilepis laureola", "impila", "Wikipedia: Callilepis laureola", ""),
        ("Xanthium strumarium", "cocklebur", p, ""),
        ("Xanthium spinosum", "spiny cocklebur", "Wikipedia: Xanthium spinosum", ""),
    ])
    rows += _many("veratrum", "Melanthiaceae", [
        ("Veratrum album", "false hellebore", p, ""),
        ("Veratrum viride", "Indian poke", "Wikipedia: Veratrum viride", ""),
        ("Veratrum californicum", "", "Wikipedia: Veratrum californicum", ""),
        ("Veratrum nigrum", "", "Wikipedia: Veratrum nigrum", ""),
        ("Veratrum oxysepalum", "", "Wikipedia: Veratrum oxysepalum", ""),
        ("Toxicoscordion venenosum", "meadow deathcamas", p, "Zigadenus venenosus"),
        ("Toxicoscordion paniculatum", "", "Wikipedia: Toxicoscordion paniculatum", "Zigadenus paniculatus"),
        ("Toxicoscordion fremontii", "", "Wikipedia: Toxicoscordion fremontii", ""),
        ("Toxicoscordion micranthum", "", "Wikipedia: Toxicoscordion micranthum", ""),
        ("Toxicoscordion exaltatum", "", "Wikipedia: Toxicoscordion exaltatum", ""),
        ("Toxicoscordion nuttallii", "", "Wikipedia: Toxicoscordion nuttallii", "Zigadenus nuttallii"),
        ("Zigadenus glaberrimus", "sandbog deathcamas", p, ""),
        ("Amianthium muscitoxicum", "flypoison", "Wikipedia: Amianthium muscitoxicum", ""),
        ("Stenanthium densum", "", "Wikipedia: Stenanthium densum", ""),
        ("Anticlea elegans", "", "Wikipedia: Anticlea elegans", "Zigadenus elegans"),
    ])
    rows += _many("cytisine", "Fabaceae", [
        ("Laburnum anagyroides", "golden chain", p, ""),
        ("Laburnum alpinum", "alpine laburnum", "Wikipedia: Laburnum alpinum", ""),
        ("Cytisus scoparius", "broom", p, ""),
        ("Dermatophyllum secundiflorum", "mescal bean", p, "Sophora secundiflora"),
        ("Gymnocladus dioicus", "Kentucky coffeetree", p, ""),
        ("Robinia pseudoacacia", "black locust", "Wikipedia: Robinia pseudoacacia", ""),
        ("Sophora tomentosa", "", "Wikipedia: Sophora tomentosa", ""),
    ])
    rows += _many("saponin", "Fabaceae", [
        ("Wisteria sinensis", "Chinese wisteria", p, ""),
        ("Wisteria floribunda", "Japanese wisteria", "Wikipedia: Wisteria floribunda", ""),
    ])
    rows += _many("solanine", "Solanaceae", [
        ("Solanum dulcamara", "bittersweet nightshade", p, ""),
        ("Solanum nigrum", "black nightshade", p, ""),
        ("Solanum americanum", "", "Wikipedia: Solanum americanum", ""),
        ("Solanum pseudocapsicum", "Jerusalem cherry", p, ""),
    ])
    rows += _many("solanine_tuber", "Solanaceae", [
        ("Solanum tuberosum", "potato", "Wikipedia: Potato", ""),
    ])
    rows += _many("solanine_tomato", "Solanaceae", [
        ("Solanum lycopersicum", "tomato", "Wikipedia: Tomato", ""),
    ])
    rows += _many("daphne", "Thymelaeaceae", [
        ("Daphne mezereum", "mezereon", p, ""),
        ("Daphne laureola", "spurge laurel", "Wikipedia: Daphne laureola", ""),
        ("Daphne gnidium", "", "Wikipedia: Daphne gnidium", ""),
        ("Daphne odora", "", "Wikipedia: Daphne odora", ""),
        ("Wikstroemia indica", "", "Wikipedia: Wikstroemia indica", ""),
    ])
    rows += _many("podophyllum", "Berberidaceae", [
        ("Podophyllum peltatum", "mayapple", p, ""),
        ("Podophyllum hexandrum", "", "Wikipedia: Podophyllum hexandrum", ""),
    ])
    rows += _many("pyrrolizidine", "Asteraceae", [
        ("Jacobaea vulgaris", "ragwort", p, "Senecio jacobaea"),
        ("Senecio vulgaris", "groundsel", "Wikipedia: Senecio vulgaris", ""),
        ("Ageratina altissima", "white snakeroot", ""),  # overwritten below
    ])
    # Ageratina is tremetone, not only PA. Remove the mistaken row and add the right toxin.
    rows[:] = [r for r in rows if r["scientific_name"] != "Ageratina altissima"]
    rows += _many("snakeroot", "Asteraceae", [
        ("Ageratina altissima", "white snakeroot", p, ""),
    ])
    rows += _many("pyrrolizidine", "Boraginaceae", [
        ("Heliotropium europaeum", "", "Wikipedia: Heliotropium europaeum", ""),
        ("Heliotropium indicum", "Indian heliotrope", p, ""),
    ])
    rows += _many("pennyroyal", "Lamiaceae", [
        ("Mentha pulegium", "pennyroyal", p, ""),
    ])
    rows += _many("oxalate", "Araceae", [
        ("Dieffenbachia seguine", "dumb cane", p, "Dieffenbachia maculata"),
        ("Arum maculatum", "cuckoo pint", p, ""),
        ("Alocasia macrorrhizos", "giant taro", "Wikipedia: Alocasia macrorrhizos", ""),
    ])
    rows += _many("urushiol", "Anacardiaceae", [
        ("Toxicodendron radicans", "poison ivy", p, ""),
        ("Toxicodendron diversilobum", "poison oak", "Wikipedia: Toxicodendron diversilobum", ""),
        ("Toxicodendron pubescens", "Atlantic poison oak", "Wikipedia: Toxicodendron pubescens", ""),
        ("Toxicodendron vernix", "poison sumac", "Wikipedia: Toxicodendron vernix", ""),
        ("Toxicodendron rydbergii", "", "Wikipedia: Toxicodendron rydbergii", ""),
        ("Toxicodendron vernicifluum", "lacquer tree", "Wikipedia: Toxicodendron vernicifluum", ""),
    ])
    rows += _many("phototoxic", "Apiaceae", [
        ("Heracleum mantegazzianum", "giant hogweed", p, ""),
        ("Heracleum sosnowskyi", "Sosnowsky's hogweed", p, ""),
    ])
    rows += _many("manchineel", "Euphorbiaceae", [
        ("Hippomane mancinella", "manchineel", p, ""),
        ("Excoecaria agallocha", "blinding mangrove", p, ""),
    ])
    rows += _many("ongaonga", "Urticaceae", [
        ("Urtica ferox", "ongaonga", p, ""),
    ])
    rows += _many("gympie", "Urticaceae", [
        ("Dendrocnide moroides", "gympie gympie", "Wikipedia: Dendrocnide moroides", ""),
    ])
    rows += _many("tutin", "Coriariaceae", [
        ("Coriaria myrtifolia", "redoul", p, ""),
        ("Coriaria arborea", "tutu", "Wikipedia: Coriaria arborea", ""),
        ("Coriaria ruscifolia", "", "Wikipedia: Coriaria ruscifolia", ""),
        ("Coriaria sarmentosa", "", "Wikipedia: Coriaria sarmentosa", ""),
        ("Coriaria nepalensis", "", "Wikipedia: Coriaria nepalensis", ""),
        ("Coriaria japonica", "", "Wikipedia: Coriaria japonica", ""),
    ])
    rows += _many("physostigmine", "Fabaceae", [
        ("Physostigma venenosum", "Calabar bean", p, ""),
    ])
    rows += _many("curare", "Menispermaceae", [
        ("Chondrodendron tomentosum", "curare vine", p, ""),
    ])
    rows += _many("antiaris", "Moraceae", [
        ("Antiaris toxicaria", "upas tree", "Wikipedia: Antiaris toxicaria", ""),
    ])
    rows += _many("dioscorine", "Dioscoreaceae", [
        ("Dioscorea hispida", "", "Wikipedia: Dioscorea hispida", ""),
    ])
    rows += _many("cleistanthus", "Phyllanthaceae", [
        ("Cleistanthus collinus", "", p, ""),
    ])
    rows += _many("saponin", "Caryophyllaceae", [
        ("Agrostemma githago", "corn cockle", p, ""),
    ])
    rows += _many("saponin", "Sapindaceae", [
        ("Aesculus hippocastanum", "horse chestnut", p, ""),
        ("Aesculus glabra", "Ohio buckeye", "Wikipedia: Aesculus glabra", ""),
        ("Aesculus pavia", "red buckeye", "Wikipedia: Aesculus pavia", ""),
    ])
    rows += _many("phytolacca", "Phytolaccaceae", [
        ("Phytolacca americana", "pokeweed", p, ""),
        ("Phytolacca dioica", "", "Wikipedia: Phytolacca dioica", ""),
        ("Phytolacca acinosa", "", "Wikipedia: Phytolacca acinosa", ""),
    ])
    rows += _many("lantana", "Verbenaceae", [
        ("Lantana camara", "lantana", p, ""),
    ])
    rows += _many("melia", "Meliaceae", [
        ("Melia azedarach", "chinaberry", p, ""),
    ])
    rows += _many("karwinskia", "Rhamnaceae", [
        ("Karwinskia humboldtiana", "coyotillo", "Wikipedia: Karwinskia humboldtiana", ""),
    ])
    rows += _many("argemone", "Papaveraceae", [
        ("Argemone mexicana", "Mexican prickly poppy", p, ""),
    ])
    rows += _many("mistletoe", "Santalaceae", [
        ("Viscum album", "European mistletoe", "Wikipedia: Viscum album", ""),
        ("Phoradendron leucarpum", "American mistletoe", "Wikipedia: Phoradendron leucarpum", "Phoradendron serotinum"),
    ])
    rows += _many("euonymus", "Celastraceae", [
        ("Euonymus europaeus", "spindle", p, ""),
        ("Euonymus atropurpureus", "", "Wikipedia: Euonymus atropurpureus", ""),
    ])
    rows += _many("duranta", "Verbenaceae", [
        ("Duranta erecta", "golden dewdrop", p, ""),
    ])
    rows += _many("opioid", "Papaveraceae", [
        ("Papaver somniferum", "opium poppy", "Wikipedia: Papaver somniferum", ""),
    ])
    rows += _many("boophone", "Amaryllidaceae", [
        ("Boophone disticha", "", "Wikipedia: Boophone disticha", ""),
    ])
    rows += _many("lycorine", "Amaryllidaceae", [
        ("Narcissus pseudonarcissus", "wild daffodil", "Wikipedia: Narcissus pseudonarcissus", ""),
        ("Lycoris radiata", "red spider lily", p, ""),
    ])
    rows += _many("actaea", "Ranunculaceae", [
        ("Actaea pachypoda", "doll's eyes", p, ""),
        ("Actaea rubra", "red baneberry", "Wikipedia: Actaea rubra", ""),
        ("Actaea spicata", "baneberry", "Wikipedia: Actaea spicata", ""),
    ])
    rows += _many("helleborus", "Ranunculaceae", [
        ("Helleborus niger", "Christmas rose", p, ""),
        ("Helleborus foetidus", "stinking hellebore", "Wikipedia: Helleborus foetidus", ""),
        ("Helleborus viridis", "", "Wikipedia: Helleborus viridis", ""),
    ])
    rows += _many("cardiac", "Ranunculaceae", [
        ("Adonis vernalis", "pheasant's eye", p, ""),
        ("Adonis aestivalis", "summer pheasant's eye", "Wikipedia: Adonis aestivalis", ""),
    ])
    rows += _many("protoanemonin", "Melanthiaceae", [
        ("Paris quadrifolia", "herb paris", p, ""),
    ])
    rows += _many("saponin", "Adoxaceae", [
        ("Sambucus ebulus", "dwarf elder", "Wikipedia: Sambucus ebulus", ""),
    ])
    rows += _many("cyanide_kernel", "Schisandraceae", [
        ("Illicium anisatum", "Japanese star anise", "Wikipedia: Illicium anisatum", ""),
    ])
    # Illicium anisatum is anisatin, not cyanide. Fix that row.
    for row in rows:
        if row["scientific_name"] == "Illicium anisatum":
            row["toxin_id"] = "anisatin"
    return rows


# Anisatin is not in TOXINS yet; add it before materialise.
TOXINS["anisatin"] = dict(
    route="ingestion", toxic_parts="fruit and seeds", toxin="anisatin",
    symptoms="Vomiting, then seizures and a falling level of consciousness.",
    onset="Usually within a few hours.",
    what_to_do="Seek emergency medical care immediately. This is not the edible star anise used in cooking.",
    severity="Can be fatal. The lookalike used as a spice is a different species.",
)


def materialise() -> list[dict]:
    """Flat rows ready for CSV and for content validation."""
    out = []
    seen = set()
    for row in species_rows():
        key = row["scientific_name"].strip()
        if not key or key.lower() in seen:
            raise SystemExit(f"duplicate or blank species in catalog: {key!r}")
        seen.add(key.lower())
        tox = TOXINS[row["toxin_id"]]
        out.append({
            "scientific_name": key,
            "kingdom": tox.get("kingdom") or "Plantae",
            "family": row["family"],
            "genus": key.split()[0],
            "common_name": row["common_name"],
            "route": tox["route"],
            "toxic_parts": tox["toxic_parts"],
            "toxin": tox["toxin"],
            "severity": tox["severity"],
            "symptoms": tox["symptoms"],
            "onset": tox["onset"],
            "what_to_do": tox["what_to_do"],
            "psychoactive": "1" if tox.get("psychoactive") else "0",
            "edibility": "deadly",
            "toxicity": "deadly",
            "source": row["source"],
            "aliases": row["aliases"],
        })
    return out


CSV_FIELDS = [
    "scientific_name", "kingdom", "family", "genus", "common_name", "route", "toxic_parts",
    "toxin", "severity", "symptoms", "onset", "what_to_do", "psychoactive", "edibility",
    "toxicity", "source", "aliases",
]


def write_csv(path) -> int:
    import csv
    from pathlib import Path

    rows = materialise()
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        w.writeheader()
        w.writerows(rows)
    return len(rows)


if __name__ == "__main__":
    from .deadly import SPECIES_CSV

    n = write_csv(SPECIES_CSV)
    print(f"wrote {n} species -> {SPECIES_CSV}")
