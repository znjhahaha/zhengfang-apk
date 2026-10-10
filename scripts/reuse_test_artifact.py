#!/usr/bin/env python3
"""Reuse a verified build when only delivery failed. Never invoke Gradle or resign."""
import argparse
import json
import os
from pathlib import Path
import re
from release_distribution import Budget, DeliveryError, REPO, gh_api, inspect_apk, verify_artifact_set

APK_INPUTS=['app','gradle','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat']

def validate_origin(run, jobs):
    if run.get('event')!='workflow_dispatch' or run.get('status')!='completed' or run.get('path')!='.github/workflows/release.yml':
        raise DeliveryError('Completed trusted test build required')
    if any(run.get(k,{}).get('full_name')!=REPO for k in ['repository','head_repository']):
        raise DeliveryError('Artifact origin must be this repository')
    if not any(j['name']=='build' and j['conclusion']=='success' for j in jobs):
        raise DeliveryError('Origin build and checks must have succeeded')

def reuse(run_id,directory,budget):
    if not re.fullmatch(r'[0-9]{1,24}',str(run_id)): raise DeliveryError('Invalid build run')
    run=gh_api(f'repos/{REPO}/actions/runs/{run_id}',budget)
    jobs=gh_api(f'repos/{REPO}/actions/runs/{run_id}/jobs?per_page=100',budget)['jobs']
    validate_origin(run,jobs)
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    budget.run(['gh','run','download',str(run_id),'--repo',REPO,'--name','release-apk','--dir',str(directory)],'reuse-tested-apk',180)
    receipt=json.loads((directory/'receipt.json').read_text())
    if receipt.get('sourceSha')!=run['head_sha'] or receipt.get('buildId')!=str(run_id):
        raise DeliveryError('Original build receipt mismatch; choose original build run')
    if os.environ.get('TAG') and 'v'+receipt['versionName']!=os.environ['TAG']:
        raise DeliveryError('Requested test tag differs from original APK')
    tests=receipt.get('tests',{})
    if not tests.get('tests') or tests.get('failures') or tests.get('errors'): raise DeliveryError('Passing tests required')
    actual=inspect_apk(directory/'app-release.apk',budget)
    if any(receipt.get(k)!=v for k,v in actual.items()): raise DeliveryError('APK differs from original build receipt')
    if receipt.get('versionCode', 0) >= 113: verify_artifact_set(directory/'app-release.apk', receipt, budget)
    budget.run(['git','diff','--exit-code',receipt['sourceSha'],'HEAD','--',*APK_INPUTS],'unchanged-apk-source',30)
    receipt['deliveryRunId']=os.environ['GITHUB_RUN_ID'];receipt['deliverySourceSha']=os.environ['GITHUB_SHA']
    (directory/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('Reusing original tested APK:',receipt['sha256'])
    return receipt

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);parser.add_argument('--directory',type=Path,default=Path('delivery'))
    args=parser.parse_args();reuse(args.run_id,args.directory,Budget(300))
