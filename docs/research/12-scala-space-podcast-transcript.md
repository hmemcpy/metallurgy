# 12 — Scala Space podcast: "The Future of Scala IDEs" (transcript)

Auto-generated transcript (YouTube captions, cleaned of rolling-window repetition;
**not manually corrected**). Source of origin context for Metallurgy.

| | |
|---|---|
| **Episode** | RE-LIVE 🔴 🎙️ *The Future of Scala IDEs with Igal Tabachnik and Jędrzej Rochala* |
| **Channel** | Scala Space |
| **Date** | 2025-01-29 |
| **Duration** | ~1h 00m |
| **URL** | <https://www.youtube.com/watch?v=SlPDmwhxeok> |
| **Participants** | Igal Tabachnik (hmemcpy) · Jędrzej Rochala (VirtusLab — Scala 3 compiler / Metals) · Łukasz (host, Scala Space) |

> Transcription caveat: YouTube auto-captions mishear many proper nouns. Reading key:
> **eal / Eagle / e** = Igal · **intellig / intj / intelligent** = IntelliJ · **scolar** = Scala ·
> **medals** = Metals · **zo** = ZIO · **beus** = (VirtusLab?) · **yan / yanj / yandre** = Jędrzej ·
> **tasty / sty** = TASTy · **basty / Bast** = BETASTy · **flux** = flags · **class P / class puff** = classpath ·
> **PSI trees / PS the trees** = PSI trees · **yre** = (Chris Kipp?) · **scalet Tre / scalar Tre** = Scala 3.
> Treat technical specifics as paraphrase, not quotation.

## Key moments

- **[06:01–08:50]** — Igal demos the **founding problem**: in Scala 2, removing one character paints
  every dependent reference red across all modules instantly (in-memory). In Scala 3 (delegating to the
  compiler), the dependent file's broken reference **stays white** — the error does not propagate
  cross-module. *This is the gap Metallurgy exists to close.*
- **[09:30–10:21]** — Secondary downside: transparent-inline/macro types inferred as `Any` (no completion,
  no highlighting). Note: this is presented as a *lesser* concern than the cross-module propagation.
- **[14:04–19:08]** — Jędrzej demos **BETASTy**: a two-module project (A depended on by B), break A, and
  show that with `-Ybest-effort` B still gets completion + diagnostics for A's symbols. "A big game changer."
- **[19:19–19:38]** — BETASTy shipped in Scala 3.5.0; backport to 3.3 LTS planned.
- **[22:02–24:30]** — Igal's "light bulb": BETASTy would make dependent broken symbols **fully red** (visual
  feedback) instead of white. Notes IntelliJ already uses `pc` for the error-highlighting stage and falls back
  to heuristics; "everything can be intercepted and augmented in IntelliJ, the API is really rich."
- **[24:42–26:52]** — Earlier experiment: Metals-via-LSP inside IntelliJ. Succeeded at macro resolution but
  was too expensive (project + metadata loaded twice). Pivoted to using `pc` **directly** to augment IntelliJ.
- **[27:04–29:28]** — Jędrzej on the prototype: `pc` needs (a) build definition (Scala version, flags) and
  (b) a populated classpath. IntelliJ gives the classpath trivially; the **population** is solved by
  piggybacking IntelliJ's own compiler-diagnostics pass (compiler-based highlighting), whose real compiler
  run writes the class/TASTy files `pc` then consumes.
- **[44:40–47:30]** — The `PresentationCompiler` Java interface (`scala.meta.pc`): completions, signature
  help, hover/type info — an LSP-like API callable directly as a library, no protocol.
- **[53:43–54:45]** — Scala 3 keeps evolving; delegating highlighting to the real compiler insulates
  IntelliJ from chasing every syntax change (e.g. `using` as first param list).
- **[55:06–57:08]** — The **"science fiction"** idea: *fake PSI trees* from compiler output to fully replace
  the bundled plugin. Igal: "this is science fiction… a team of 20 senior engineers for several years."
  Pragmatic counter: "let's just start with plugging into existing extension points… and if we're hungry
  for more." Metallurgy is the pragmatic path; the fake-PSI replacement is the explicit non-goal.
- **[58:15–end]** — Mutual credit: Igal inspired Jędrzej's experiments (the IntelliJ macro fix); Jędrzej's
  BETASTy work inspired Igal. Public ask: publish the experiment on GitHub (→ Metallurgy).

## Full transcript

```
[00:00]  to another scolar space podcast today with me I have two scholar developers both experienced and both very fun um first with me is eal tabachnik um a
[00:13]  developer who wrote um intellig plugins and most notably zo for intellig plugin which helps with all the uh typing stuff that you can have in zo and
[00:23]  the second one is Yan who uh is my colleague from beus and is also a contributor to um Scala compiler and medals so our topic for today
[00:35]  is the future of Scala IDs and we wanted to talk about new developments in this area new experiments and things that are becoming a new reality things
[00:48]  that can be done things that are made possible in the near future so um coming from that I would like to ask ask youle about um current
[01:02]  state of intelligent you have the most experience here uh with this uh side of the story and could you U brief tell tell us briefly um what
[01:14]  the state of intellig col is all right well first of all thank you very much for having me here it's real pleasure so uh I've been a
[01:23]  power user of intellig for a very long time for various reasons and wrot some plugins into that so I have some perspective into some inner workings of
[01:33]  intellig but I want to emphasize up front that I'm speaking from a personal experience and all all of this is my personal opinion and speculations in no
[01:43]  way reflect this the the actual things that jet brains are working on and so with that I wanted to H say that historically intellig is a giant
[01:55]  ecosystem that lets you build languages on top of it basically everything in intellig every language support is a plug-in so everything could be extended and implemented they
[02:04]  have a basic uh layer on top of which they build every language and historically what they did is in in order for them to support all of
[02:15]  their feature features uh efficiently and effectively they're essentially reimplemented most of the compilers H into their own representation this means that for most languages out there intellig
[02:27]  does not invoke the actual compiler to do analysis and to do stuff like syntax highlighting and type resolution but instead they uh they do it themselves and
[02:40]  store everything uh in caches and in memory and building indexes indices uh that I'm sure many of you have seen at one point or another in order
[02:48]  for uh in order to interact with the code without actually delegating to the compiler uh for performance for performing analysis and they've done this historically for performance
[03:03]  reasons for for the latency that you sometimes have when you need to invoke a compilation step and wait for the results and for the most part this
[03:13]  worked really well and this continues to work really well uh including uh for Scala now Scala is notoriously a difficult language to support uh but they've done
[03:24]  that and in the recent years the support has greatly improved so they a very few if at all false positives that historically happened and this is of
[03:34]  course because Scala support was kind of reimplemented by jetbrains ER and not using the real compiler so sometimes the compiler and what you see on the screen
[03:44]  are not in agreement but in the recent years I truly believe that it's greatly improved and now with Scala 3 they actually are using the real Scala
[03:54]  compiler for some things uh in particular error Diagnostics and and error highlighting uh that actually give you much higher Fidelity uh for the features and for the
[04:07]  for for what you see on the screen so it's much closer to the the the truth now of course there are some downsides to to to that
[04:14]  as well H which will cover very shortly okay so uh so what are the um biggest you know impactful points of doing this what is the um
[04:30]  story what are the benefits of doing this the way that jet brains did it right so one of the benefits as I would mention as I mentioned
[04:40]  is probably uh in terms of something that's called error recover error recovery so this means that when you type code every time you type something H intellig
[04:50]  immediately reanalyses it and it's able to inspect the the state of your code and if you're in the middle of typing and things are not really compiling
[05:01]  yet it will immediately highlight all the errors in your code and all the things that are not currently resolved this this greatly affects the speed of development
[05:10]  the feedback that you get that you're still kind of the your code is still kind of not in compiling State and in addition because of that you
[05:18]  have uh other features that are supported from intellig even if your code is not compiling so you can still have navigation you can still have uh you
[05:26]  can still import Stu you can still refactor a code that's actually red you can still move it around with with the refactoring tools and this is of
[05:35]  course because uh their their representation is in memory it does not need to ask the compiler uh give me uh like compile this code for me and
[05:45]  tell me if it's okay or not in fact this is one of the limitations is that code that does not compile H the the compiler will not
[05:54]  probably give you the full information uh that you can display uh we'll talk about about that in a little bit in fact I want to show uh
[06:01]  this error recovery in action so I'm going to share my screen and I want to show you the little difference that that you can see so I'm
[06:10]  going to present my screen screen now uh and I'm going to switch to uh intellig you can see this right so here I have the tiniest application
[06:22]  so this create a class person which declared somewhere else in fact I'm going to open this up and I'm going to uh split this into two tabs
[06:33]  like this so what happens is this is Scala two now for Scala two everything happens in memory so the moment I change the code in such a
[06:40]  way that it no longer compass for example I'm going to erase a character right now everything immediately becomes red across all files in the project so across
[06:52]  all modules so without saving just by removing one character everything is painted in Red so intelligy knows that right now I'm in not in a compiler not
[06:59]  in a compiling State and this is without performing any compilation this is all done by intellig in memory now if I switch to Scala 3 I have
[07:10]  another project here that's running Scala 3 and I have a very similar setup and I'm going to open a open this file and you can see I
[07:18]  already tried this before but I'm going to do something very similar I'm going to uh erase one character now what happens is it's may be very hard
[07:27]  to see in a video but first of all there's a slight DeLay So when I remove this things happening not immediately there's there's a maybe a second
[07:36]  or half a second of delay that's happening here that's one thing uh so it knows that that in this file this symbol person is no longer valid
[07:45]  so it paints it in red and this squiggly is just for for the type of detection but notice that here in the main this uh this element
[07:53]  is not highlighted this is white right now and SC has no idea what this is because and this is my my guess right now that H the
[08:05]  Scala compiler did not provide any information to intellig about the status of this file from Main because this file is not compiling okay so it doesn't propagate
[08:15]  this error information everywhere so to demonstrate the difference is that the Scala 3 uh Scala 3 by default delegates to the compiler but you can switch this
[08:24]  off in settings if you go to the settings and uh in the editor you can select the error compiler the error highlighting to be uh to use
[08:35]  the built-in rules and when you do this even in Scala 3 you have the immediate feedback just as it was in Scala 2 but of course there
[08:44]  are downsides to all of this and right now I'm going to switch it back to the compiler so I don't forget and I'm going to stop sharing
[08:50]  and uh one of the downsides so the the benefits are of course that you have much more responsive feedback when you right writing code the downside of
[09:00]  course is because Scala had to re implement intellig the Scala team intellig basically had to reimplement a lot of the functionality there are some things that they
[09:10]  just literally technically cannot Implement one of the the major examples of this is macro support So macros are is something that only the compiler is aware of
[09:20]  and it does not expose there's not enough information uh for jet brains to support macros correctly for two there's some some hooks into in the ID so
[09:30]  you can write custom support for macros but in Scala 3 is even worse uh because of the inclusion of a new feature in Scala 3 called transparent
[09:43]  in lines which lets you write macros as kind of as a first class citizen whereas in scalar 2 macros were were always defined declared experimental in Scala
[09:52]  3 this is kind of a first class citizen which many libraries now use uh and unfortunately intellig cannot really display uh those things correctly it will not
[10:03]  infer the types produced by those macros so it will be inferred as any in most cases and this is why you will not get any helpful completion
[10:11]  or helpful compilation hints or even syntax highlighting will probably not work and uh this is one of the downsides of not using the the real compiler directly
[10:21]  for those things but uh we as we'll find pretty pretty soon we there might be improvements in that area so with that I would like to H
[10:32]  pass it along to well back to youash to hear about how metals does this yeah I I just wanted to um you know interrupt you because as
[10:42]  a long-term metos user um I do have this experience of type providers being aw awesome so you can write transparent inline macros that generate types in the
[10:56]  compilation yeah and it is impossible for any tooling that doesn't interact with the compiler to know what is the type coming from this so um yanj it's
[11:05]  a question to you could you describe how this is possible on the metal side how Metals is integrated with a compiler that it is possible and what
[11:17]  are the upsides and downsides of that yeah so maybe let me start with the Bas is what is a metals because people may not be aware that
[11:25]  there is a second ID which is uh working uh for scy developers so Metals is kind of a different story than intj it is not a big
[11:36]  ecosystem it is rather a smaller implementation a backend for your language support and it can be used in various various clients such as Visual Studio code uh
[11:46]  or new or even the new IDs such as Z Helix uh they all support language server protocol and this is how it communicates with the backend so
[11:58]  Metals is something smaller is as I said not a part of something bigger small independent part which is only uh supporting Scala so with this we went
[12:10]  for a bit different solution uh because uh as e got stated intell have their own reimplementation of the compiler for for various reasons we went for a
[12:22]  different solution which uses the real Scala compiler uh the same one that is uh running behind scalar build tool or uh other grle uh yeah basically this
[12:37]  allowed us to uh support the features such as transparen line macros as I said this is the true Scala compiler which uh only Cuts few phases it
[12:49]  runs front end phases and skips the and it allows us to have correct representation it is uh the same as the compiler will output it to to
[13:01]  other tools so this is a giant benefit to have because as developers we need to have a solid knowledge about our code and this is the only
[13:10]  way to guarantee that the uh stuff that ID is showing in displaying it to you is correct the the same as it is represented internally in the
[13:20]  compiler and this had a small um drawback the solution that we've we've chosen because uh uh our solution doesn't store anything in memory it is using the
[13:33]  real compiler and compiler have different phes front end phases and then back end phases which creates compilation outputs in turn in Scala it's class files and sty
[13:42]  files and those faes are actually not run if your project is not compiling we have no class files that means that sometimes when you are working with
[13:51]  partially not compiling project we will not have information about those files that are not compiling so uh we've had some brainstorm and we basically uh came up
[14:04]  with something clever called best effort compilation and it reuses another stuff that was created for Scala fre called tasty so let me quickly introduce T because this
[14:18]  is very important for for the future of of of Scala this is the typed abstract syntax tree this is basically a tasty uh but what it means
[14:29]  it's the internal representation of the compiler with proper types which is dumped the file system which then can be Reed by other tools such as compiler or
[14:38]  even metals or for some analysis uh which can also be done so this is a great utility to have and we will L leverage tasty to allow
[14:51]  us to work with non-c compiling projects by providing the best effort compilation and best effort compilation basically Ally allows us for early compilation uh outputs even if
[15:03]  your project is not compiling it will still create uh partially not correct tasty files which are called B tasty best effort compilation and this allows us to
[15:16]  have information about non-c compiling projects and let me Demo it for you because it may sound complicated and the result is something that is most important here
[15:24]  so um let me find the this should be it here can you see the screen yes yes okay so this is very very small scalar project and
[15:36]  as you can see it consists of two modules module a and module B let me show the build SBT file it is very very simple what is
[15:44]  important here is that module a is used by module B depends on module a and what right now we are not having any best effort compilation so
[15:55]  let me simulate uh changes which will make modu not compiling so I have this uh API object which provides us with get module name and missing given
[16:07]  method and as you can see we have two compilation errors but we want to use those methods or we are already using them in our other projects
[16:17]  and we would like to complete this so let me try to do this module a and we see nothing right there is no completion for module a
[16:26]  I can even try to write this this module a test how this method was called once again it was called get module a name and what is
[16:38]  important here we are not even getting the Diagnostics because it is not compiling as dependent project is not compiling is not propagating further so that for compilation
[16:48]  uh will be triggered in this build by changing Scala version to the latest one because it is a change which was implemented internally in the compiler we
[16:57]  have to bump Scala version to support it so uh let me quickly swap it for the latest Scala RC version enter import the build Metals import build
[17:11]  and after a few seconds if we go to the same file we can still see uh we can still see that there are errors here one is
[17:20]  the missing type one is the incorrect type and now if you try to use it here let me try to complete this module we can see the
[17:32]  module here there is no package defined so it's EMP it's say it's empty but if we go and try to complete the members of this object we'll
[17:40]  get both module a and missing given method and we can complete this everything is fine let's say I will make a compilation error here there is no
[17:49]  such name as get module a name uh we have proper Diagnostics despite our project which depend on is still providing us with errors but what is super
[18:00]  nice here is even changing uh and fixing those issues right now will uh allow us to uh quickly see the results uh in the ID so right
[18:12]  now you can see that there is a get module a name it was cached but if I Reas the compiler for the completions for missing given it
[18:19]  no longer says any it is non-existing type and if we change this to let's say a string it will also instantly say that this is no longer
[18:29]  uh a non-existing type this is a string so everything is updated dynamically and those methods which are non- compiling as we had in the beginning so the
[18:38]  type was actually non-existing we just had any so it is still better than nothing uh but not actually uh super useful for us but we still have
[18:49]  the completions so it's a win-win so that's basty demo and it is a big game changer for the future of Scala ID for Metals uh we will
[18:56]  have a good support for projects which are non-c compiling and this is actually not so rare to have a changing Branch updating a module is a common
[19:08]  thing to do and right now we will have support for it and yeah so when are we getting that well that's the question as you saw I
[19:19]  had to switch to the latest release County date I think nightly already has it and uh it will be shipped in 3.5.0 and that's for the
[19:30]  our main line and for LTS it will be shipped in a near future I can tell you the exact de but it will probably be shipped to
[19:38]  the LTS so all users could benefit from this and as Scala provides you with uh is is Backward Backward Compatible uh you should always stay on the
[19:49]  latest version and enjoy the better ID experience and yeah that's it about beasty this this is really great from my perspective this this is exactly the kind
[20:01]  of thing that I personally was missing when when using and by the way just just so I'm clear you were using Metals only you were using Vim
[20:08]  instead of like VSC because Metals is just a language server that you can plug into any any editor exactly this is what happened this and all you
[20:18]  did was just up bumped a Scala version right you use yes I only had to change the Scala version because I already released my the pr Branch
[20:29]  for the metals because Metals haven't merged the support for Bast yet it will be merged probably next week uh it is almost done we are just waiting
[20:37]  for ACI to pass and yeah okay so this happening soon right yes it is happening really really soon so if you are not on the latest version
[20:47]  uh Scala 3.5.0 should be also released very very soon like two weeks or one um you can try it for yourself uh using metals yeah and see
[20:56]  if it helps helps in your case if not feel free to always report issues that's the way for us to know that there is something to improve
[21:06]  and without the the issues we don't know that something is not working so uh sure yeah it's it's it's a game changer uh for me um I'm
[21:13]  a bit salty because it's coming to 3.5 and I'm mostly maintaining Li libraries now so I have to wait for the LTS uh backport but um you
[21:24]  know having that an app code that looks really really nice but I have a question yeah sorry sorry so LTS means 3.3 yeah yeah yeah so it's
[21:37]  going to be back ported to 3.3 okay yes yes probably 99 cool okay I wanted to continue in the same vein so Eagle um do you see
[21:51]  any ways um having your knowledge you know not uh developer at jet brains but long-term um power user of intellig do you see any way for um
[22:02]  idea to benefit from this development uh absolutely I mean personally right now a light bulb went went in my head that this this little basty demo would
[22:14]  actually solve this little uh highlighting issue that I just demonstrated when switching to scalet 3 because the compiler the output of basty would provide you with more
[22:24]  highlight information ER H for for other things for other modules so we would highlight that that person inside the main file the instead of being white it
[22:37]  would be fully Red non-comp iling symbol which is very important for uh developer like visual feedback um like intelligy knows that something is broken there but you
[22:47]  as a developer don't really see it be because because it's white so for this is going to be very beneficial now as I mentioned before this is
[22:57]  mostly spec calculation but the way I know that this currently works is that Scala for intellig for Scala 3 projects uses um the real compiler or as
[23:07]  as it more commonly known the presentation compiler uh to just for this just for the D just for the error highlighting stage it does not use it
[23:19]  for anything else currently and in if it cannot do it it falls back to its own internal uh heuristics just as we saw but but what we
[23:28]  saw here is that it it opened the possibility for definitely enrich intellig with the information from the real compiler because it's it's kind of deep I'm not
[23:40]  sure if it's it's if it's even a public API uh but because intellig already today knows how to compile and read the tasty output this is just
[23:53]  a matter of enriching it now whether or not it would be possible to do externally by means of implementing some plugin for example because everything can be
[24:01]  uh kind of intercepted and augmented in intelligent the API is really rich in that regard and if not this is definitely something that the team at intellig
[24:13]  could probably a jet brins could probably do if we open a pool request or if we open an issue saying uh Scala 3.5 and above now supports
[24:20]  this B tasty format maybe maybe it would be possible to use it I'm sure if if this conversation starts they will they could definitely consider it now
[24:32]  uh having said this is that I want to kind of move into the the little bit of experimentation that we did that I initially wanted to see
[24:42]  uh whether or not it would be possible to implement Metals Inside intellig by using LSP so LSP for those who are not really familiar LSP is this
[24:55]  uh protocol that allows talking to the language server to metals and this is how for example Metals was able to be integrated into Vim so LSP allows
[25:04]  integrating language servers into any editor whatsoever so intellig recently shipped support for LSP it's all only in the ultimate edition but there are another LSP Alternatives uh
[25:17]  implementations available so what I wanted to do is I say I I wanted to see if I could uh have intellig talk to medals and uh recover
[25:28]  some diagnos some additional information from it in particular I wanted to see if if it can resolve uh the macros the things that we talked about that
[25:38]  were missing earlier if it can do that I wanted to say I want to say that I succeeded there were some some initial uh successes of course
[25:45]  it's not not really perfect and I had to kind of dig deep into the macro resolution uh facilities of intellig to to to support it but I
[25:55]  was happy that it was kind of successful but then as it turned out ER I met yandre and we realized that using LSP inside of intellig with
[26:09]  Metals running uh on the back end is H it's very very expensive it's like in terms of both performance and just the memory uh you were essentially
[26:19]  loading your project and all the metadata twice into memory uh so that's not really uh not really something that we want to do and then we started
[26:32]  talking and then we realized that we could use parts of what powers Metals the the the parts that are coming from Metals the presentation compiler to supplement
[26:42]  and to augment and to enrich the things that uh that are provided in intellig without actually doing the heavy lifting of of using LSP and with that
[26:52]  I would actually like to ask yent about uh your opinions of whether or not this was possible or successful and yeah so so we did the experiment
[27:04]  right we tried to do this uh and I would say we succeeded uh we had two different prototypes I will show them to you in a few
[27:13]  seconds but let me quickly describe what they were trying to achieve one of them was basically uh trial in which we plucked Scala presentation compiler the heart
[27:26]  of the completions heart of the hover the thing that actually computes them uh directly to the intell with skipping all this LSP stuff Let's ignore the LSP
[27:36]  connection with the server all the build tool stuff and just try to ruse the thingies we can get from the intell EJ so presentation compiler requires few
[27:48]  things first of them is the build definition we need the Scala version we need to know the flux we need to know flux this is very very
[28:00]  important to you know have proper proper completions and proper outputs from the presentation compiler the second thing is to actually have data to work with and this
[28:09]  data is a class P and uh there are two problems with this first getting the class puff and then populating the class puff so getting the class
[28:18]  puff was actually super trival in intj it does it for us because it supports reading the SBT configuration we can just get the class directly but how
[28:26]  do we at Metals do it via the bsp build server protocol it runs another server in the background which tries to compile your project and This Server
[28:38]  creates class files which are then used by the presentation compiler and this is like another server that we had to do but that's how it was implemented
[28:48]  and designed in mind so uh how did we solve this it turns out that uh the comp compiler Diagnostics which eal eal uh showcased previously are doing
[28:58]  this for us for free because they are using the real compiler and this real compiler actually runs back in phases and they populate the class PA so
[29:06]  we can run compiler Diagnostics in intell they will create class files for us and then we will consume them in metals uh in in this prototype sorry
[29:17]  this is not Metals this is another hcky experiment the in the real scalar presentation compiler to get completions uh which are true for your configuration and for
[29:28]  your setup so this was the let me interrupt you for a moment so let me get this correctly um intellig the option that eagle has shown before
[29:39]  it uses the full compiler with all the phes yes yes it has to because it provides it creates the class files uh I mean maybe I think
[29:47]  it runs the Full Compilation because class files are actually generated in those folders is that the reason for the Slowdown that eagle was mentioning that yes probably
[29:58]  yes it's the same thing we have in metals right we have to we communicate with build tool and it has it is running a bit slower than
[30:04]  than than intelligent builtin in memory representation in it has to write the this right and yeah in fact it has to be saved right the changes has
[30:13]  to be saved H with with the command s to to actually trigger the build yeah okay so this is also another area but I don't know if
[30:22]  I should Del into this this can also be solved in metals because presentation compiler uh runs three phases uh or just front end phases and commands cooking
[30:32]  which creates hyperlinks this is not important but what is important is that uh with this those front end phases we can get most errors straight by providing
[30:40]  C completion so whenever you type something we can also get diagnostics for this compilation uh this is also the true compilation output the same one you will
[30:53]  get uh maybe not full because it will not run all the later phases but partially correct compilation output and we can show this information instantly to the
[31:02]  user by just getting it from the compiler we are not doing it right now it has few problems but we will probably ship it soon or sooner
[31:09]  we don't know this is another experiment this is not important here this is just a mention that we are trying to solve this too uh but let
[31:17]  me go back to the first topic so this is the first experiment in which we try to plug the Scala presentation compiler which is provided by the
[31:26]  compiler it is bundled together uh as the Scala Library it is part of the compilation pipeline release process and then uh we are trying to plug it
[31:35]  into intell J and it worked it was working fine and it solved and uh most of the issues with the transparent L macros but there is another
[31:46]  story behind this because uh trans macros are not the only thing you need from the ID you also need to know uh the symbols available in your
[31:56]  scope and do it by providing you with symbol search so uh intellig has its own implementation of symbol search luckily presentation compiler was designed in a way
[32:06]  that we can plug any symbol search we want so if we could create a rapper around intelligent symbol search and use its indexes to get completions in
[32:13]  metals we will have a big big big uh new plugin which has true output true completions and through uses intell indexes so there is no work duplicated
[32:26]  because presentation compiler is kind of fast uh it is not super heavy because everything is provided for by intellig it already does it and yeah with those
[32:34]  building blocks we created something uh which I'm going to show you right now okay can you see the screen yes yes okay okay so right here the
[32:51]  blue ID is intj and I opened here the custy uh and let's try to get some completions right it will just work it is normal intelligent experience
[33:04]  everything is fine and just to Showcase you that metals can be pluged directly into intellig we've created a plugin which basically overwrites the implementation of the completion
[33:19]  just completions for now because it is not so trivial it requires work it was rather than the experiment than an actual try to to replace inell plugin
[33:28]  uh this is the same project same uh code as you can see the edits are still here let's try to uh do the same thing and right
[33:38]  now we are using the completions coming from Metals as you can see they a bit different looks different yeah yeah they look different because they are not
[33:48]  colored in the same way H I think they can be aligned but uh it wasn't the point of this experiment to align the visual part of the
[33:57]  completions the clue was to run the official Scala 3 presentation compiler as a back end for completions in intellig so we can see that everything is working
[34:06]  fine and it even works for symbol search so symbol search let me go back to the first ID is something that we can do uh by writing
[34:18]  capital letters in intell J so let's say we want to access concurent map we can write concurrent and you can see even if this uh thing is
[34:25]  not in scope uh it is not not important anywhere uh anywhere we can still see this as the completion because it was found as a match in
[34:33]  the index in the database and if you get this we have Auto completions right so uh I tried to bundle this index of intellig into the index
[34:43]  of metals and we can do the same thing here and uh let me try was concurrent map right concurrent map it's still here it's should displayed differently
[34:55]  as we said and this will also solve the issue with transparent inline macros I don't have sneet ready for this but it will also solve issue with
[35:03]  this because it is the real compilation running underneath with full knowledge of types um computed during the if I so just just so I I I understand
[35:15]  this completely uh what what you've done so this is a plugin that you had to write that does not replace the original we we will talk about
[35:25]  this in the end there is no way to replace to completely replace this the Scala plugin that's in intellig it's tightly integrated into intellig and some of
[35:34]  these things we will never be able to replace but what's happening here if I understand that that you created a basically you implemented a completion provider this
[35:43]  is some this is a part of the the the Scala the jetbrain SDK and this completion provider goes and uses the uh presentation compiler so essentially the
[35:53]  real compiler to asks to ask for all available symbols to give it this point and this is what you would get if you were using it from
[36:02]  Metals yes exactly this will be the almost the same because Metals has it own indexes indexes can be a bit different right that there can be implementation
[36:09]  differences in how intell builds indexes and how met build indexes but the clue is that uh we somehow bundled them together and build a new completion provider
[36:22]  on top of existing intell features so as I as you said this is exactly it it is a intellig with compilations computed by Scala free presentation compiler
[36:32]  the back end of metals okay so should we think about this experiment as a as an extension to Scala plugin to intell like a plugin for a
[36:43]  plugin I mean well if Community will want something like this and we will see enthusiasm and maybe some Community effort to to help this happen it may
[36:51]  be the case because what is uh very very good about Metals is actually the back end the scalar free presentation compiler it is super well polished it
[37:02]  is working very very good it is very very fast and I would say this is the uh most uh stable most maintained part of the idea we
[37:13]  have uh right now so using it is I think a good way it would also uh make a single piece uh single source of completions for all
[37:22]  IDs right we will then we could then work together on the same implementation because right now Intel EJ works on its own completions Metals works on Scala
[37:31]  fre presentation compiler it would be great if we could bundle our strength together and create even better experience for all of us because I don't think that
[37:41]  metal intelligy is in kind some kind of rization it's to different ecosystem built for different use cases for different people um yeah sure I F agree this
[37:52]  is really really exciting development and um I hate to be a Bas skill but you know it is something that I can't avoid I have to ask
[38:01]  what is the story versioning because you know with intellig um my experience always was that um they handle Scala versioning problems on their side so if we
[38:12]  had multiple versions of uh scholar language in the same project let's say um it was transparent to the user in the end you just don't care as
[38:24]  a user which version is used in which mod module and scal plugin basically works for all of them if you have you know modules that use Scala
[38:32]  2 they work better if you use if you have modules with scalar free they have some hiccups especially with the new uh features and new new possible
[38:41]  combinations of features that's understandable right but it's on their side the ball is on their side of the pool so um what is the actual story if
[38:53]  we wanted to do this with versioning of uh scal packages what happens if we had let's say Scala 3.2 package would it would this work because if
[39:01]  I understood you correctly before you mentioned that um the presentation compiler is part of the compiler and compiler is versioned by language versions right so it's coming
[39:13]  when in 3.5 what what about the older versions would that work okay so let me quickly explain what I meant by this bundled together so presentation compiler
[39:23]  uses internals of the compiler it uses the internal representation and this representation can evolve over time as it's internal right that's the natural thing that happens so
[39:34]  we uh went into a conclusion that we should bundle and release them together so each version of the compiler has its own specific presentation compiler so let's
[39:45]  say we have scalar 3.3.3 it has its own presentation compiler released for it and there will be no updates for it in the future you will have
[39:54]  to buom the Scala version that's the price that we p but with Scala free Binary compatibility guarantees that's that shouldn't be a problem you should try to
[40:02]  stay on the latest version if you can uh because LTS is Backward Compatible uh I think in both uh uh binary and Source level yeah that's that's
[40:14]  a good news for for you know end users programmers who use Scala for development of business applications but for Library maintainers the only hope if I understand
[40:24]  you correctly is that uh the um back Port of BC comes back to LTS Branch right okay so this is the basty part right because if we
[40:37]  talk about the presentation compiler improvements we always backport all of them I mean there is no exclusion all PR that happen on the main line we backport
[40:46]  all of them to the latest version yes because uh we haven't had any problems with it yet if we will have any problems we will maybe then
[40:54]  reconsider this right now everything is working as in Ed so we try to go and bump LTS ID support to the latest stable version from the main
[41:03]  line all the changes to presentation compiler happening on let's say 3.5 Branch are coming back to LTS yes yes oh nice all CH all changes and I
[41:13]  think as LTS I think is also going to be released very very soon it is up to date to the 3.5.0 it will be on par in
[41:21]  terms of the features uh so as you can see it it is all only for the ID part right the compiler is is not part of this
[41:28]  compiler is more strict because it's LTS line we only backport the necessary stuff they have critical fixes uh optimizations not all the stuff but for ID we
[41:39]  can backport everything uh everything is also um this is possible because presentation compiler is basically an interface and we have Mima setup for it and we just
[41:52]  can't break it uh I mean it's very very hard to break it so we just load it with service loaders so now I can go and answer
[41:59]  your question because you you said how it will work with different SC versions right so right now on the screen you can see the internals of this
[42:07]  plugin for the intell it is not super big as you can see just few Scala files and in the uh PC completion provider part we need something
[42:18]  called presentation compiler so I have this service created and Method get presentation compiler and what it it basically is the map which holds uh a module uh
[42:32]  as a key and a value is the presentation compiler released for this version so each module that you have will have its own presentation compiler it is
[42:43]  also necessary because uh there is not only Escala version you know going into presentation compiler there are Java flag Scala flag class Puffs you have to separate
[42:52]  them as as I mentioned previously presentation compiler requires all of them to provide with correct output let's say you have additional dependency on the class paff you
[43:01]  will see extra completions uh despite they are not actually present uh in your module so presentation compiler will see something but the real compiler run by SBT
[43:11]  won't see it because you pass incorrect configuration incorrect setup that's why we have this map and each uh instance in each module that you're working on it
[43:21]  will be populated dynamically uh not instantly for all modules you just download load it with service loader and then you will have this support right okay so
[43:32]  so my last n pick that I I have and I promise it's the last one so what what what happens if the module has uh Scala version
[43:39]  set to two let's say two for okay so 213 is Scala 2 and I'm talking about Scala three I've been talking about Scala three okay so it's
[43:50]  exclusive for now I mean not yet no because uh this is kind of tricky uh in Scala 3 uh maybe maybe let me change the narrative so
[44:01]  there was a presentation compiler and it was always a part of the LSP implementation we've had Scala ID we had Metals we had Doty language server uh
[44:10]  and for some time the implementation of presentation compiler was located directly in the compiler but after some time it was moved to Metals it was removed as
[44:23]  Doty language server was deprecated and moved back to the metals and then it was moved back to the compiler because it was a mistake and we wanted
[44:31]  this uh equality of compiler internals and uh the stuff that we are working on yeah makesense and uh that let us that Scala 3E right now from
[44:44]  version 3.4 3.3.0 has its own presentation compiler they are not present for 3.2.0 but I said that we migrated them from metals to presentation compiler so metals
[44:55]  have presentations compilers so I'm not calling them Scala free presentation compilers they're called mxs but they are exactly basically the same thing they're re implemented for some
[45:06]  features they had to be uh but they're exactly extending the implementing the same implementation this presentation compiler abstract class which provides you with the completions with the
[45:17]  item resolving signature help hovers so as long as you have something that implements this class you can load it and actually mxs are released for Scala 22
[45:31]  22 23 and yeah you can just have it all together and the patching part is you know this is just a change in artifact name you don't
[45:41]  download or. Scala L Scala free presentation compiler you just download uh Metals uh M Tax for specific compiler version always for specific compiler version as I said
[45:52]  they are very they have to be binary compatible okay this sounds I I wanted to ask about this file that we're seeing in the screen so this
[46:00]  is the interface essentially to the presentation compiler right so you can implement it and I see so many useful things here for like from from a tooling
[46:08]  perspective it it gets you everything like I see it gets you completions if you get back a completion list you get back signature help so uh hover
[46:18]  gives you back type information the thing you see on the tool tip so it reminds me of the LSP API but this is a library this is
[46:26]  something that's can be called directly without the need to use a protocol and and yes and and so this this is fantastic now just going back a
[46:39]  little bit to this overall idea of making a plugin for a plugin from my perspective as somebody who's who's done that I've built plugins for plugins in
[46:48]  intellig here's what here's there's some technical issues that that that are challenging about this first of all you have to find all the EXT points uh to
[46:58]  overwrite First the the completion provider it's kind of a more supported feature because you can Implement your own languages you obviously want to provide your own code
[47:05]  completions H so you have completion providers for other things that you would want to support for example error highlighting there are some error highlight it's all run
[47:14]  in stages there are error highlighting stages H I'm not 100% sure how pluggable that is because it's very tightly coupled to to to how actually Scala run
[47:27]  it so it needs we need to do some more experimentation looking if it can be overridden and there's there are many many other hooks that could be
[47:34]  utilized from from presentation compiler now whether or not this is a it it it only can be a plugin for a plugin so like I mentioned the
[47:44]  Scala compiler the Scala plugin in intelligy is not going to go anywhere because it's tightly integrated into the ecosystem that that's that's intelligent it it cannot be
[47:54]  removed or replaced with anything but it can be augmented using these hooks using these apis and I think this gives an amazing optin experience for people who
[48:04]  are more adventurous and they want to experiment by enabling even more features from the real compiler rather than just error highlighting they can enable completions they can
[48:17]  enable uh other things that I currently cannot think of maybe maybe a signature hints with hover that would give you a much richer information uh for example
[48:27]  for things that that intellig doesn't understand like transparent in lines stuff like that and I definitely envisioned this as an at least Community effort to build something
[48:38]  like this and again not speaking at any capacity for jet brains or or what their goals are if one day they can see that this is these
[48:46]  These are these experiments are successful then maybe they will choose to integrate some of these things uh into the plugin so it will just enrich the the
[48:58]  experience for everybody um and that's it so I'm I'm really excited about this development I would really love to see it going open source and although there
[49:06]  are about 20 people in the Scala world that know how to deal with intelligy plugins H who also contribute to open source I'm pretty sure this can
[49:18]  be managed managed yes uh I only like to add that this idea of augmenting is I think a very nice approach to to go further with this
[49:30]  experiment because it could be a fall back for the stuff that intell can't understand or intell could be fall back for this you can it can be
[49:36]  the both ways and it could also solve the issue with macros right if if you see that something is any it's a good guess that uh well
[49:45]  maybe try using the real compiler to to get the types for you and yeah that's that's very very promising I will stop the share screen right now
[49:55]  I think more thing to mention is that the reason it kind of has to be well at least for the time being a plugin for a plugin
[50:03]  because it has to kind of piggyback of what the intellig already does which means running a real compilation for you because it has to it currently only
[50:12]  works if there are tasty files available or be tasty in the future uh which means it has to run at least some compilation step for you to
[50:22]  to generate the class path and generate the target files so it it always has to kind of piggyback or or consume what intellig already does so it's
[50:31]  always about augmentation yeah yes because if you would want to skip this part of augmenting you would basically want to plug Metals into in and it's already
[50:42]  possible there are various LSP plugins that can can achieve this there's official LSP plugin made by jet brains with the ultimate license you can just start Metals
[50:49]  with it and uh it should work I'm not sure if it was working but it should work yeah the syntax highlighting hover everything there is also LSP
[50:58]  for intell plugin made by direct Hut which even has metal setup guide in the documentation which is all the third party uh I would say implementation of
[51:09]  LSP for intj so if you want Metals in intell there are different ways to get this but as e stated intell is a big ecosystem it is
[51:20]  not only the part that you're editing uh the comp the integration with other languages like like Java it all built upon the indexes and that stuff and
[51:27]  by using LSP you will not have this so you may not see the symbols in Java projects from Scala you may lose other features and yeah you
[51:38]  will also lose those very very good uh insights that uh you know you used list do uh head option uh do map or something or filter and
[51:49]  it said you can just use find don't use head option filter and you will also lose this because all based on PSI the internal representation of intell
[51:58]  so you can't exactly dump the intj it is a different different tool for different people it is a big ecosystem but well you can still augment this
[52:10]  augmenting I think is the way to go yeah looks looks very promising and I'm hopeful I I just had a brain wave that there for a moment
[52:19]  that um I I worried about Scala 2 support but um Eagle you mentioned that the compilation support for error reporting it's only done for Scala 3 right
[52:30]  it's not Scala would just work based on I think well just to answer to this there's some ways to use I believe well not zinc it's it's
[52:40]  already used zinc but there is a way to switch this for Scala 2 but it would actually have to it's nonperformance so this is why it's not
[52:46]  recommended uh in my opinion Scala 2 uh support as much as it is it's done it's not going to be improved uh so H micro libraries such
[52:54]  as they are would either have to provide their own support or just move to scalet Tre so for example intelligence ships with a custom support for couple
[53:04]  of shapeless stuff for the new type macro and I believe for for scaly dering and there are a couple of third party libraries uh that ship their
[53:15]  own kind of plugin into intelligy to teach them about this mro but this is has to be a very very specific uh support and in my opinion
[53:25]  it's not worth anymore like it would be very very difficult if not impossible to to to try and backport support for Scala 2 uh for any of
[53:32]  this so Scala 3 forward in fact there's one more thing that we haven't mentioned other than uh like other than things like micros or transparent inlines we
[53:43]  Al also have to remember that scalar 3 uh is actively evolving all the time and so one of the drawbacks that I kind of didn't mention early
[53:53]  on is that intellig the intellig team the scalar team at intellig they have to because of the reimplementation they have to support all of those syntax changes
[54:01]  so uh for example moving around or allowing you to to access uh putting the the the usings list as a first parameter list this is a breaking
[54:12]  significantly breaking change that has to be uh manually like actively supported inside intellig otherwise code that compiles will show up as red in in intellig because the
[54:26]  the the PS the trees the the parsers will be broken so they always have to kind of fix that and I believe that by utilizing by delegating
[54:33]  some of the error highlighting and syntax highlighting to the real compiler this could be uh helped inside intelligence so they you know we we can get an
[54:45]  additional benefit there as well in terms of language features that just keeps keeps getting added yeah but uh that's uh that would be very nice to to
[54:56]  try and make them work together but there's also one other approach which is very very uh experimental I mean it is just the idea uh that we
[55:06]  could fake PSI trees the fake scal internal representation based on the compiler output and this is something that would solve the issues uh with the support for
[55:19]  with Java and other languages but it is super hard to do and it will take long time to get it right but but that would basically replace
[55:26]  the intellig plugin at all right because if we could use metals and fake those PSI trees we we could then dit into EJ plugin yeah but I
[55:35]  think this is science fiction I think thison that would require a team of 20 Senor Engineers for several years yeah know also you would ditch all the
[55:46]  nice stuff that in scar plugin has so all the rewrites with the suggestions no because you would fake those trees Al so they run on the trees
[55:58]  themselves yes you still have trees but you try to map the scalar representation back to the inter representation yeah but it doesn't really make sense that those
[56:06]  uh specific you know language specific um rewrite hints would be part of the intelligent itself they would be in scal plugin itself right you could then cut
[56:16]  them up it's open source we could borrow them yeah plug yeah but of course there's a big mismatch and you mentioned by the way there's a great
[56:30]  talk by yre I think we should include the link somewhere in the notes for this episode there's a great talk with with a shout out to me
[56:38]  thank you so much h i was it was really Pleasant surprised uh just first of all showcasing a little bit how to actually achieve what we' seen
[56:46]  here today and also explaining a little bit of a difference between how intellig does things versus how uh the presentation comp does things and uh the G
[56:56]  the bridging of this Gap I imagine it's it's a ton of work it might be possible but let's just start with I believe it would be beneficial
[57:08]  just starting with plugging into existing uh exension points in intellig uh and seeing if if that brings additional benefit and if we're hungry for more I mean
[57:18]  it could right yeah I mean I'm sure they're they're big intelligent out there because I use it primarily because I like the integrated environment and I got
[57:32]  used to the fact that that ER reporting is real like was real Snappy for me but then again I'm mostly using Scala 2 still unfortunately um but
[57:39]  uh but yeah I [Music] [Music] mean this can only get better from here sure I would like to remind our viewers that it is possible to ask
[57:50]  us question and have our guests answer them so unfortunately I can't see any questions asked on either YouTube or twitch and I see viewers on both so
[58:04]  don't be shy ask away and um I think we have covered all of the stuff that we had for today so if we don't have any questions
[58:15]  we will probably be cling I'd like to thank eal at this point because he inspired me to do all this work he was the inspiration behind my
[58:26]  experiments thank you I was inspired by your intell macro fix and I that led me to to know to experiment with the the rest of the stuff
[58:35]  so well thank you and I shamelessly wanted to be on this podcast to hear a little bit more about B tasty because that's a real significant Improvement
[58:44]  for the experience of using Metals whether or not it's vs Cod or a Vim or Zed which by the way there's there's Zed which I I managed
[58:54]  to contribute a small Scala support plugin based on Metals so if it's working on Linux since yesterday so if you want a very fast editor that's written
[59:03]  in Rust and it's it's a very very healthy open- Source ecosystem I recommend you check out Zed because it's really fast basically fast well it's rust H
[59:12]  one last thing that I would like to request is that you make your experiment uh published on GitHub so we can all take a look at it
[59:22]  and improve it of course try to to I will try to do this and send the link should be in the description of the on Twitter maybe
[59:31]  or or in the link on the YouTube somewhere I can do this right now it will take some time but I I promise I will do this
[59:41]  okay um we don't have any questions really we just have a comment that says great job so yeah let's skip it up and let's uh let's try
[59:49]  and publish this a new development this new po let's let's hope it gets to the POC level PC this this this presentation comp yes yes I got
[60:03]  it so let's let's get it published and uh we'll publish a link there will be um you know a link on redit and I will post it
[60:13]  also to uh discords in the scholar Community with uh all the links that we have to the things that we have discuss today and thank you so
[60:19]  much eel andj for coming to this podcast and and see you in the future dear skola users cheers bye thank you
```
