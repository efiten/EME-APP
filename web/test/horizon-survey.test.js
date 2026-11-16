import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { readExpected } from './expected.js';
import { parseSurvey, readPoints } from '../src/lib/horizon-survey.js';
import { encode, visibility } from '../src/lib/horizon.js';

const DIR = fileURLToPath(new URL('../../app/src/test/resources/horizon_survey/', import.meta.url));
const file = (name) => readFileSync(DIR + name, 'utf8');

describe('horizon-survey import (same table as HorizonSurveyTest.kt)', () => {
  test('surveys as in the shared table', () => {
    const rows = readExpected('horizon_import');
    expect(rows.length).toBe(9);
    for (const r of rows) {
      const got = parseSurvey(file(r.file));
      if (r.error === '') {
        expect(got.status, r.file).toBe('OK');
        expect(got.points, r.file).toBe(Number(r.points));
        expect(encode({ minElDeg: 0, obstacles: got.obstacles }), r.file).toBe(r.horizon);
        expect(got.gaps.map(([a, b]) => `${a}-${b}`).join(' '), `${r.file} gaps`).toBe(r.gaps);
      } else {
        expect(got.status, r.file).toBe(r.error);
      }
    }
  });

  test('JSON and CSV of the same survey agree; the calibration point in the CSV does not count', () => {
    expect(parseSurvey(file('horizon_survey_example.csv'))).toEqual(parseSurvey(file('horizon_survey_example.json')));
  });

  test('never more optimistic than the measurement', () => {
    const ok = parseSurvey(file('horizon_survey_example.json'));
    const h = { minElDeg: 0, obstacles: ok.obstacles };
    for (const [az, el] of readPoints(file('horizon_survey_example.json'))) {
      if (el > 0) expect(visibility(h, az, el - 0.01), `az ${az}`).toBe('BLOCKED');
    }
    expect(visibility(h, 85, 25)).toBe('CLEAR');
  });

  test('broken JSON is not a survey', () => {
    expect(parseSurvey('{ "points": [ { "az": 1').status).toBe('NOT_A_SURVEY');
    expect(parseSurvey('').status).toBe('NOT_A_SURVEY');
    expect(parseSurvey('{"points":[]}').status).toBe('TOO_FEW_POINTS');
  });
});
