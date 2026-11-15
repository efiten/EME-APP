// @vitest-environment jsdom
import { describe, expect, test } from 'vitest';
import {
  CHAT_CQ_WINDOW_MINUTES,
  HB9Q_BANDS,
  bandByName,
  loggerRows,
  messageMillis,
  parseCq,
  parseRows,
  recentCqs,
  recentParticipants,
} from '../src/lib/hb9q.js';
import { resource } from './fetch-stub.js';

const millis = (iso) => Date.parse(iso);

describe('ChatParserTest', () => {
  const rows = resource('hb9q_logger_rows.html');

  test('parsesAllRows', () => {
    const msgs = parseRows(rows);
    expect(msgs).toHaveLength(63);
    expect(msgs.filter((m) => m.isCq)).toHaveLength(12);
  });

  test('cqMessage', () => {
    const m = parseRows(rows)[0];
    expect(m.id).toBe(1990523);
    expect(m.time).toBe('07:16');
    expect(m.fullTime).toBe('Fri 6th of Nov - 07:16');
    expect(m.callsign).toBe('SP5GDM');
    expect(m.name).toBe('Jan');
    expect(m.recipient).toBeNull();
    expect(m.isCq).toBe(true);
    expect(m.text).toBe('***** CQ 1296.060 1st Q65-60C CFOM 1500Hz *****');
  });

  test('directedMessage', () => {
    const m = parseRows(rows).find((x) => x.callsign === 'NJ2R' && x.recipient !== null);
    expect(m.recipient).toBe('KD2XN');
    expect(m.text).toBe('Phil are you around for a try?');
    expect(m.isCq).toBe(false);
  });

  test('noEmailAddressesInParsedData', () => {
    for (const m of parseRows(rows)) {
      const s = JSON.stringify(m);
      expect(s.includes('@') && s.includes('.com')).toBe(false);
    }
  });

  test('senderIdForReplies', () => {
    expect(parseRows(rows)[0].senderId).toBe('212');
  });

  test('emptyOrGarbage', () => {
    expect(parseRows('')).toEqual([]);
    expect(parseRows('<tr><td>x</td></tr>')).toEqual([]);
  });

  test('markupInTextStaysText', () => {
    const html =
      '<tr id="msg_2"><td>07:16</td><td>&lt;img src=x onerror=alert(1)&gt;</td><td>@</td><td>F5AOL</td><td><span>Pascal</span></td></tr>';
    const [m] = parseRows(html);
    expect(m.text).toBe('<img src=x onerror=alert(1)>');
    // Even when the raw markup is in the cell, it is never turned into an element.
    const raw = '<tr id="msg_3"><td>07:16</td><td><img src=x onerror=alert(1)></td><td>@</td><td>F5AOL</td><td></td></tr>';
    expect(parseRows(raw)[0].text).toBe('');
  });
});

describe('ChatCqParserTest', () => {
  const now = millis('2026-11-08T09:10:00Z');

  const msg = (text, time = '09:01', call = 'F5AOL', name = 'Pascal') => ({
    id: 1, time, fullTime: `Sun 8th of Nov - ${time}`, callsign: call, name, recipient: null, text, isCq: false, senderId: '1',
  });

  const fields = (text) => {
    const c = parseCq(msg(text), now);
    return [c.freq, c.period, c.mode, c.cfom, c.hz].join(' ');
  };

  test('realWorldExamples', () => {
    expect(fields('***** CQ 1296.088 1st Q65-60C CFOM 1500 2.5m 100W *****')).toBe('1296.088 1st Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****')).toBe('1296.065 2nd Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.085 1st Q65-60C, CFOM @ 1500 hz. 1.5m/Superpatch. *****')).toBe('1296.085 1st Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.077 2nd ***** GE CQ 1296.077 2nd Q65-60b, CFOM@1500 ***** *****')).toBe('1296.077 2nd Q65-60B CFOM 1500');
    expect(fields('***** CQ 1296.070 1st **Q65-60C** CFOM 1500hz *****')).toBe('1296.070 1st Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.075 2nd Q65-60C CFOM 1500 Hz *****')).toBe('1296.075 2nd Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.065 1st - Q65 - >>30b< -1500Hz - CFOM>> *****')).toBe('1296.065 1st Q65-30B CFOM 1500');
    expect(fields('***** CQ 1296.070 1st Q65c-60C 1500Hz CFOM 10 min *****')).toBe('1296.070 1st Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.075 1st CFOM 1500Hz Q65-60C *****')).toBe('1296.075 1st Q65-60C CFOM 1500');
    expect(fields('***** CQ 1296.090 1st Q65-30B CFOM @1500 ***** *****')).toBe('1296.090 1st Q65-30B CFOM 1500');
    // Cyrillic С in the mode
    expect(fields('***** CQ 1296.085 1st Q65-60С CFOM 1500 ***** *****')).toBe('1296.085 1st Q65-60C CFOM 1500');
  });

  test('missingPartsBecomeQuestionMarks', () => {
    expect(fields('***** CQ 1296. 1st Thank you very much for the first DIGI contact. *****')).toBe('1296.? 1st ? ? ?');
    expect(fields('***** CQ 432.065 JT65B *****')).toBe('432.065 ? JT65B ? ?');
    expect(fields('***** CQ 1296.050 2nd CW own echo *****')).toBe('1296.050 2nd CW own echo ?');
    expect(fields('***** CQ 1296.040 1st Q65-60A 1200Hz *****')).toBe('1296.040 1st Q65-60A ? 1200');
  });

  test('q65WithLongPeriods', () => {
    expect(fields('***** CQ 10368.100 1st Q65-120E CFOM 1000 *****')).toBe('10368.100 1st Q65-120E CFOM 1000');
    expect(fields('***** CQ 1296.065 1st Q65-300A CFOM 1000 *****')).toBe('1296.065 1st Q65-300A CFOM 1000');
    expect(fields('***** CQ 1296.065 2nd Q65-300A CFOM *****')).toBe('1296.065 2nd Q65-300A CFOM ?');
  });

  test('ordinaryMessagesAreNotCq', () => {
    expect(parseCq(msg('089 -332 0 0900 2.7 -10 : CQ F5AOL JN38'), now)).toBeNull();
    expect(parseCq(msg('@KB2SA: Thanks for the reports.'), now)).toBeNull();
    expect(parseCq(msg('stop CQ 075'), now)).toBeNull();
  });

  test('lineContainsCallAndName', () => {
    const c = parseCq(msg('***** CQ 1296.088 1st Q65-60C CFOM 1500 2.5m 100W *****'), now);
    expect(c.time).toBe('09:01');
    expect(c.callsign).toBe('F5AOL');
    expect(c.name).toBe('Pascal');
  });

  test('recent_lastHalfHourNewestFirstWithoutDuplicates', () => {
    const list = [
      { ...msg('***** CQ 1296.088 1st Q65-60C CFOM 1500 *****', '09:01', 'F5AOL'), id: 5 },
      { ...msg('***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****', '08:50', 'OE3GWC'), id: 4 },
      { ...msg('***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****', '08:50', 'OE3GWC'), id: 3 }, // posted twice
      { ...msg('***** CQ 1296.085 1st Q65-60C CFOM 1500 *****', '08:30', 'R4CX'), id: 2 }, // older than 30 min
    ];
    expect(recentCqs(list, now, 30).map((c) => c.callsign)).toEqual(['F5AOL', 'OE3GWC']);
  });

  test('recent_defaultWindowIsSixtyMinutesLikeLiveCq', () => {
    expect(CHAT_CQ_WINDOW_MINUTES).toBe(60);
    const list = [
      { ...msg('***** CQ 1296.088 1st Q65-60C CFOM 1500 *****', '08:25', 'F5AOL'), id: 2 }, // 45 min old
      { ...msg('***** CQ 1296.085 1st Q65-60C CFOM 1500 *****', '08:05', 'R4CX'), id: 1 }, // 65 min old
    ];
    expect(recentCqs(list, now).map((c) => c.callsign)).toEqual(['F5AOL']);
  });

  test('unparseableTimeIsDroppedExplicitlyInsteadOfShowingAsEpochZero', () => {
    const bad = msg('***** CQ 1296.075 1st Q65-60C CFOM 1500 *****', '');
    expect(messageMillis(bad, now)).toBeNull();
    expect(parseCq(bad, now)).toBeNull();
    expect(recentCqs([bad], now, 30)).toEqual([]);
  });

  test('messageTimeUsesDateFromFullTime', () => {
    const old = { id: 1, time: '09:05', fullTime: 'Sat 7th of Nov - 09:05', callsign: 'K6EME', name: 'Matt', recipient: null, text: '***** CQ 1296.075 *****', isCq: true, senderId: null };
    expect(messageMillis(old, now)).toBe(millis('2026-11-07T09:05:00Z'));
    expect(recentCqs([old], now, 30)).toEqual([]);
  });

  test('messageTimeWithoutDateJustBeforeMidnight', () => {
    const lateNow = millis('2026-11-08T23:59:00Z');
    const justSent = { id: 1, time: '23:57', fullTime: '', callsign: 'K6EME', name: 'Matt', recipient: null, text: '***** CQ 1296.075 *****', isCq: true, senderId: null };
    expect(messageMillis(justSent, lateNow)).toBe(millis('2026-11-08T23:57:00Z'));
    expect(recentCqs([justSent], lateNow, 30)).toHaveLength(1);
    const earlier = { ...justSent, id: 2, time: '23:30' };
    expect(messageMillis(earlier, millis('2026-11-08T08:00:00Z'))).toBe(millis('2026-11-07T23:30:00Z'));
  });

  test('realChatPage', () => {
    const msgs = parseRows(resource('hb9q_rows_8nov.html'));
    const cqs = msgs.map((m) => parseCq(m, now)).filter((c) => c !== null);
    expect(cqs.length, `aantal CQ's: ${cqs.length}`).toBeGreaterThanOrEqual(18);
    const f5aol = cqs.find((c) => c.callsign === 'F5AOL');
    expect(f5aol.freq).toBe('1296.088');
    expect(f5aol.name).toBe('Pascal');
  });
});

describe('Hb9qClientTest', () => {
  test('aGenuinelyEmptyChatHasAnEmptyLoggerField', () => {
    expect(loggerRows('{"status":"success","logger":""}')).toBe('');
  });

  test('rowsArePassedThroughUnchanged', () => {
    expect(loggerRows('{"status":"success","logger":"<tr>...</tr>"}')).toBe('<tr>...</tr>');
  });

  test('aMissingLoggerFieldIsTreatedAsAnErrorNotAnEmptyChat', () => {
    expect(() => loggerRows('{"status":"success"}')).toThrow("HB9Q: antwoord zonder 'logger'-veld");
  });

  test('notJsonIsAnUnexpectedAnswer', () => {
    expect(() => loggerRows('<html>login</html>')).toThrow('HB9Q: unexpected answer');
  });

  test('statusOtherThanSuccessIsAnError', () => {
    expect(() => loggerRows('{"status":"error","logger":"x"}')).toThrow('HB9Q: data not available');
    expect(() => loggerRows('{"logger":"x"}')).toThrow('HB9Q: data not available');
  });
});

describe('ChatStoreTest', () => {
  const msg = (id, time, call, name) => ({ id, time, fullTime: '', callsign: call, name, recipient: null, text: 'test', isCq: false, senderId: String(id) });

  test('recentParticipantsWithTime', () => {
    const messages = [
      msg(5, '09:03', 'UA3PTW', 'Dmitrij'),
      msg(4, '09:01', 'F5AOL', 'Pascal'),
      msg(3, '08:50', 'ON5GS', 'Dirk'),
      msg(2, '08:37', 'OE3GWC', 'Gerd'),
      msg(1, '08:30', 'F5AOL', 'Pascal'),
    ];
    const p = recentParticipants(messages, 'ON5GS');
    expect(p.map((x) => `${x.time} ${x.callsign} ${x.name}`)).toEqual(['09:03 UA3PTW Dmitrij', '09:01 F5AOL Pascal', '08:37 OE3GWC Gerd']);
  });
});

describe('bands', () => {
  test('bands', () => {
    expect(HB9Q_BANDS).toHaveLength(13);
    expect(HB9Q_BANDS.map((b) => `${b.name}/${b.id}/${b.label}`)).toEqual([
      'B50/1/50', 'B144/2/144', 'B222/3/222', 'B432/4/432', 'B902/5/902', 'B1296/6/1296', 'B23XX/7/23xx',
      'B3400/10/3400', 'B5760/9/5760', 'B10G/8/10xxx', 'B24G/11/24048', 'B47G/12/47088', 'B76G/13/76032',
    ]);
    expect(bandByName('B1296').id).toBe(6);
    expect(bandByName('nope')).toBeUndefined();
  });
});
